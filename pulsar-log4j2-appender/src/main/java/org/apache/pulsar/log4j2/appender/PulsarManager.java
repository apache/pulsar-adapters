/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.log4j2.appender;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.apache.logging.log4j.core.config.Property;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.TypedMessageBuilder;

public class PulsarManager extends AbstractManager {

    public static final String PRODUCER_PROPERTY_PREFIX = "producer.";
    static Supplier<ClientBuilder> PULSAR_CLIENT_BUILDER = PulsarClient::builder;

    private PulsarClient client;
    private Producer<byte[]> producer;

    private final String serviceUrl;
    private final String topic;
    private final Property[] properties;
    private final String key;
    private final boolean syncSend;

    public PulsarManager(final LoggerContext loggerContext,
                         final String name,
                         final String serviceUrl,
                         final String topic,
                         final boolean syncSend,
                         final Property[] properties,
                         final String key) {
        super(loggerContext, name);
        this.serviceUrl = Objects.requireNonNull(serviceUrl, "serviceUrl");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.syncSend = syncSend;
        this.properties = properties;
        this.key = key;
    }

    @Override
    public boolean releaseSub(final long timeout, final TimeUnit timeUnit) {
        if (producer != null) {
            try {
                producer.closeAsync().get(timeout, timeUnit);
            } catch (Exception e) {
                // exceptions on closing
                LOGGER.warn("Failed to close producer within {} milliseconds",
                        timeUnit.toMillis(timeout), e);
            }
        }
        return true;
    }

    public void send(final byte[] msg) {
        if (producer != null) {
            String newKey = null;

            if (key != null && key.contains("${")) {
                newKey = getLoggerContext().getConfiguration().getStrSubstitutor().replace(key);
            } else if (key != null) {
                newKey = key;
            }


            TypedMessageBuilder<byte[]> messageBuilder = producer.newMessage()
                    .value(msg);

            if (newKey != null) {
                messageBuilder.key(newKey);
            }

            if (syncSend) {
                try {
                    messageBuilder.send();
                } catch (PulsarClientException e) {
                    LOGGER.error("Unable to write to Pulsar in appender [" + getName() + "]", e);
                }
            } else {
                messageBuilder.sendAsync()
                        .exceptionally(cause -> {
                            LOGGER.error("Unable to write to Pulsar in appender [" + getName() + "]", cause);
                            return null;
                        });
            }
        }
    }

    public void startup() throws Exception {
        createClient();
        ProducerBuilder<byte[]> producerBuilder = client.newProducer()
                .topic(topic)
                .blockIfQueueFull(false);
        if (syncSend) {
            // disable batching for sync send
            producerBuilder = producerBuilder.enableBatching(false);
        } else {
            // enable batching in 10 ms for async send
            producerBuilder = producerBuilder
                    .enableBatching(true)
                    .batchingMaxPublishDelay(10, TimeUnit.MILLISECONDS);
        }
        Map<String, Object> producerConfiguration = propertiesToProducerConfiguration();
        if (!producerConfiguration.isEmpty()) {
            producerBuilder.loadConf(producerConfiguration);
        }
        producer = producerBuilder.create();
    }

    private void createClient() throws PulsarClientException {
        Map<String, Object> configuration = propertiesToClientConfiguration();

        // must be the same as
        // https://pulsar.apache.org/docs/en/security-tls-keystore/#configuring-clients
        String authPluginClassName = getAndRemoveString("authPlugin", "", configuration);
        String authParamsString = getAndRemoveString("authParams", "", configuration);
        Authentication authentication =
                AuthenticationFactory.create(authPluginClassName, authParamsString);
        boolean tlsAllowInsecureConnection =
                Boolean.parseBoolean(
                        getAndRemoveString("tlsAllowInsecureConnection", "false", configuration));

        boolean tlsEnableHostnameVerification =
                Boolean.parseBoolean(
                        getAndRemoveString("tlsEnableHostnameVerification", "false", configuration));
        final String tlsTrustCertsFilePath =
                getAndRemoveString("tlsTrustCertsFilePath", "", configuration);

        boolean useKeyStoreTls =
                Boolean.parseBoolean(getAndRemoveString("useKeyStoreTls", "false", configuration));
        String tlsTrustStoreType = getAndRemoveString("tlsTrustStoreType", "JKS", configuration);
        String tlsTrustStorePath = getAndRemoveString("tlsTrustStorePath", "", configuration);
        String tlsTrustStorePassword =
                getAndRemoveString("tlsTrustStorePassword", "", configuration);

        ClientBuilder clientBuilder =
                PULSAR_CLIENT_BUILDER.get()
                        .loadConf(configuration)
                        .tlsTrustStorePassword(tlsTrustStorePassword)
                        .tlsTrustStorePath(tlsTrustStorePath)
                        .tlsTrustCertsFilePath(tlsTrustCertsFilePath)
                        .tlsTrustStoreType(tlsTrustStoreType)
                        .useKeyStoreTls(useKeyStoreTls)
                        .enableTlsHostnameVerification(tlsEnableHostnameVerification)
                        .allowTlsInsecureConnection(tlsAllowInsecureConnection)
                        .authentication(authentication);
        if (!serviceUrl.isEmpty()) {
            clientBuilder.serviceUrl(serviceUrl);
        }
        client = clientBuilder.build();
    }

    private Map<String, Object> propertiesToClientConfiguration() {
        return propertiesToConfiguration(false);
    }

    private Map<String, Object> propertiesToProducerConfiguration() {
        return propertiesToConfiguration(true);
    }

    private Map<String, Object> propertiesToConfiguration(boolean producerProperties) {
        return Arrays.stream(properties).filter(property -> property.getValue() != null
                && property.getName().startsWith(PRODUCER_PROPERTY_PREFIX) == producerProperties)
                .collect(Collectors.toMap(
                        property -> producerProperties ?
                                property.getName().substring(PRODUCER_PROPERTY_PREFIX.length()) : property.getName(),
                        Property::getValue));
    }

    private static String getAndRemoveString(
            String name, String defaultValue, Map<String, Object> properties) {
        Object value = properties.remove(name);
        return value != null ? value.toString() : defaultValue;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public String getTopic() {
        return topic;
    }

}
