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
package org.apache.pulsar.spark;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.conf.ConsumerConfigurationData;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.receiver.Receiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A spark streaming receiver for pulsar.
 */
public class SparkStreamingPulsarReceiver extends Receiver<byte[]> {

    private static final Logger LOG = LoggerFactory.getLogger(SparkStreamingPulsarReceiver.class);

    private String serviceUrl;
    private Map<String,Object> clientConfig;
    private ConsumerConfigurationData<byte[]> consumerConfig;
    private Authentication authentication;
    private PulsarClient pulsarClient;
    private Consumer<byte[]> consumer;

    public SparkStreamingPulsarReceiver(
        String serviceUrl,
        ConsumerConfigurationData<byte[]> consumerConfig,
        Authentication authentication) {
        this(StorageLevel.MEMORY_AND_DISK_2(), serviceUrl, new HashMap<>(), consumerConfig, authentication);
    }

    public SparkStreamingPulsarReceiver(
            String serviceUrl,
            Map<String,Object> clientConfig,
            ConsumerConfigurationData<byte[]> consumerConfig,
            Authentication authentication) {
        this(StorageLevel.MEMORY_AND_DISK_2(), serviceUrl, clientConfig, consumerConfig, authentication);
    }

    public SparkStreamingPulsarReceiver(StorageLevel storageLevel,
                                        String serviceUrl,
                                        ConsumerConfigurationData<byte[]> consumerConf,
                                        Authentication authentication) {
        this(storageLevel, serviceUrl, new HashMap<>(), consumerConf, authentication);
    }

    public SparkStreamingPulsarReceiver(StorageLevel storageLevel,
        String serviceUrl,
        Map<String,Object> clientConfig,
        ConsumerConfigurationData<byte[]> consumerConfig,
        Authentication authentication) {
        super(storageLevel);

        checkNotNull(serviceUrl, "serviceUrl must not be null");
        checkNotNull(consumerConfig, "ConsumerConfigurationData must not be null");
        checkNotNull(clientConfig, "Client configuration map must not be null");
        checkArgument(consumerConfig.getTopicNames().size() > 0, "TopicNames must be set a value.");
        checkNotNull(consumerConfig.getSubscriptionName(), "SubscriptionName must not be null");

        this.serviceUrl = serviceUrl;
        this.authentication = authentication;

        if (consumerConfig.getMessageListener() == null) {
            consumerConfig.setMessageListener((MessageListener<byte[]> & Serializable) (consumer, msg) -> {
                try {
                    store(msg.getData());
                    consumer.acknowledgeAsync(msg);
                } catch (Exception e) {
                    LOG.error("Failed to store a message : {}", e.getMessage());
                    consumer.negativeAcknowledge(msg);
                }
            });
        }
        this.clientConfig = clientConfig;
        this.consumerConfig = consumerConfig;
    }

    public void onStart() {
        try {
            ClientBuilder builder = PulsarClient.builder().serviceUrl(serviceUrl).authentication(authentication);
            if (!clientConfig.isEmpty()) {
                builder.loadConf(clientConfig);
            }
            pulsarClient = builder.build();
            consumer = ((PulsarClientImpl) pulsarClient).subscribeAsync(consumerConfig).join();
        } catch (Exception e) {
            LOG.error("Failed to start subscription : {}", e.getMessage());
            restart("Restart a consumer");
        }
    }

    public void onStop() {
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (pulsarClient != null) {
                pulsarClient.close();
            }
        } catch (PulsarClientException e) {
            LOG.error("Failed to close client : {}", e.getMessage());
        }
    }
}