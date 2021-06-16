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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.ClientBuilderImpl;
import org.apache.pulsar.client.impl.TypedMessageBuilderImpl;
import org.testng.annotations.BeforeMethod;

abstract class AbstractPulsarAppenderTest {
    static final String LOG_MESSAGE = "Hello, world!";
    private final String resourceName;
    protected ProducerBuilder<byte[]> producerBuilder;
    protected ClientBuilderImpl clientBuilder;
    protected PulsarClient client;
    protected Producer<byte[]> producer;
    protected List<Message<byte[]>> history;

    protected LoggerContext ctx;

    protected AbstractPulsarAppenderTest(String resourceName) {
        this.resourceName = resourceName;
    }

    class MockedMessageBuilder extends TypedMessageBuilderImpl<byte[]> {

        MockedMessageBuilder() {
            super(null, Schema.BYTES);
        }

        @Override
        public MessageId send() {
            synchronized (history) {
                history.add(getMessage());
            }

            return mock(MessageId.class);
        }

        @Override
        public CompletableFuture<MessageId> sendAsync() {
            synchronized (history) {
                history.add(getMessage());
            }

            return CompletableFuture.completedFuture(mock(MessageId.class));
        }
    }

    static Log4jLogEvent createLogEvent() {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(AbstractPulsarAppenderTest.class.getName())
                .setLoggerFqcn(AbstractPulsarAppenderTest.class.getName())
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(LOG_MESSAGE))
                .build();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        history = new LinkedList<>();

        client = mock(PulsarClient.class);
        producer = mock(Producer.class);
        clientBuilder = mock(ClientBuilderImpl.class);

        doReturn(client).when(clientBuilder).build();
        doReturn(clientBuilder).when(clientBuilder).serviceUrl(anyString());
        doReturn(clientBuilder).when(clientBuilder).loadConf(any());
        doReturn(clientBuilder).when(clientBuilder).authentication(any());
        doReturn(clientBuilder).when(clientBuilder).tlsTrustStorePassword(anyString());
        doReturn(clientBuilder).when(clientBuilder).tlsTrustStorePath(anyString());
        doReturn(clientBuilder).when(clientBuilder).tlsTrustCertsFilePath(anyString());
        doReturn(clientBuilder).when(clientBuilder).tlsTrustStoreType(anyString());
        doReturn(clientBuilder).when(clientBuilder).useKeyStoreTls(anyBoolean());
        doReturn(clientBuilder).when(clientBuilder).enableTlsHostnameVerification(anyBoolean());
        doReturn(clientBuilder).when(clientBuilder).allowTlsInsecureConnection(anyBoolean());

        producerBuilder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(producerBuilder);
        doReturn(producerBuilder).when(producerBuilder).topic(anyString());
        doReturn(producerBuilder).when(producerBuilder).producerName(anyString());
        doReturn(producerBuilder).when(producerBuilder).enableBatching(anyBoolean());
        doReturn(producerBuilder).when(producerBuilder).batchingMaxPublishDelay(anyLong(), any(TimeUnit.class));
        doReturn(producerBuilder).when(producerBuilder).blockIfQueueFull(anyBoolean());
        doReturn(producerBuilder).when(producerBuilder).loadConf(any());
        doReturn(producer).when(producerBuilder).create();
        doReturn(CompletableFuture.completedFuture(null)).when(producer).closeAsync();

        when(producer.newMessage()).then(invocation -> new MockedMessageBuilder());
        when(producer.send(any(byte[].class)))
                .thenAnswer(invocationOnMock -> {
                    Message<byte[]> msg = invocationOnMock.getArgument(0);
                    synchronized (history) {
                        history.add(msg);
                    }
                    return null;
                });

        when(producer.sendAsync(any(byte[].class)))
                .thenAnswer(invocationOnMock -> {
                    Message<byte[]> msg = invocationOnMock.getArgument(0);
                    synchronized (history) {
                        history.add(msg);
                    }
                    CompletableFuture<MessageId> future = new CompletableFuture<>();
                    future.complete(mock(MessageId.class));
                    return future;
                });

        PulsarManager.PULSAR_CLIENT_BUILDER = () -> clientBuilder;

        ctx = Configurator.initialize(
                "PulsarAppenderTest",
                getClass().getClassLoader(),
                getClass().getClassLoader().getResource(resourceName).toURI());
    }

    protected LogEvent deserializeLogEvent(final byte[] data) throws IOException, ClassNotFoundException {
        final ByteArrayInputStream bis = new ByteArrayInputStream(data);
        try (ObjectInput ois = new ObjectInputStream(bis)) {
            return (LogEvent) ois.readObject();
        }
    }
}