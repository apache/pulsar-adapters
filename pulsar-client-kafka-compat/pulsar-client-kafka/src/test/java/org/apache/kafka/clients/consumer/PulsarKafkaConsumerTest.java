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
package org.apache.kafka.clients.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.codec.binary.Hex;
import org.apache.kafka.clients.constants.MessageConstants;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.util.MessageIdUtils;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.naming.TopicName;
import org.junit.Assert;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.IObjectFactory;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

@PrepareForTest({TopicName.class, MessageIdUtils.class})
@PowerMockIgnore({"org.apache.logging.log4j.*"})
public class PulsarKafkaConsumerTest {

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    @Test
    public void testPulsarKafkaConsumerWithHeaders() throws Exception {
        PowerMockito.mockStatic(TopicName.class);
        PowerMockito.mockStatic(MessageIdUtils.class);

        TopicName topicName = mock(TopicName.class);

        doReturn("topic").when(topicName).getPartitionedTopicName();

        ClientBuilder mockClientBuilder = mock(ClientBuilder.class);
        Consumer consumer = mock(Consumer.class);
        MessageId msgId = mock(MessageId.class);
        MessageMetadata messageMetadata = new MessageMetadata();
        messageMetadata.setPublishTime(System.currentTimeMillis());

        Map<String, String> headerMap = new HashMap<>();
        String header1 = MessageConstants.KAFKA_MESSAGE_HEADER_PREFIX + "header1";
        String kafkaHeader = MessageConstants.KAFKA_MESSAGE_HEADER_PREFIX + header1;
        headerMap.put(kafkaHeader,
                Hex.encodeHexString(header1.getBytes()));
        Message<byte[]> msg =
                new MessageImpl<byte[]>("topic", "1:1", headerMap, "string".getBytes(), Schema.BYTES,
                        messageMetadata);


        PulsarClient mockClient = mock(PulsarClient.class);
        PulsarClientImpl mockClientImpl = mock(PulsarClientImpl.class);

        CompletableFuture<Integer> mockNoOfPartitionFuture = new CompletableFuture<Integer>();
        mockNoOfPartitionFuture.complete(1);

        doReturn(mockClientBuilder).when(mockClientBuilder).serviceUrl(anyString());
        when(TopicName.get(any())).thenReturn(topicName);
        doReturn(mockClient).when(mockClientBuilder).build();

        when(mockClientImpl.getNumberOfPartitions(anyString())).thenReturn(mockNoOfPartitionFuture);

        Properties properties = new Properties();

        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Collections.singletonList("pulsar://localhost:6650"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "my-subscription-name");


        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumerSpy =
                spy(new PulsarKafkaConsumer<>(properties, new IntegerDeserializer(), new StringDeserializer()));

        doNothing().when(pulsarKafkaConsumerSpy).seekToEnd(anyCollection());
        PowerMockito.whenNew(PulsarKafkaConsumer.class).withAnyArguments().thenReturn(pulsarKafkaConsumerSpy);

        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumer =
                new PulsarKafkaConsumer<>(properties, new IntegerDeserializer(), new StringDeserializer());

        pulsarKafkaConsumer.received(consumer, msg);
        pulsarKafkaConsumer.poll(100);
        pulsarKafkaConsumer.close();


        Assert.assertNotNull(msg.getProperty(kafkaHeader));

    }

    @Test
    public void testPulsarKafkaConsumer() throws Exception {
        PowerMockito.mockStatic(TopicName.class);
        PowerMockito.mockStatic(MessageIdUtils.class);

        TopicName topicName = mock(TopicName.class);

        doReturn("topic").when(topicName).getPartitionedTopicName();

        ClientBuilder mockClientBuilder = mock(ClientBuilder.class);
        Consumer<byte[]> consumer = mock(Consumer.class);
        Message<byte[]> msg = mock(Message.class);
        MessageId msgId = mock(MessageId.class);

        PulsarClient mockClient = mock(PulsarClient.class);

        doReturn(mockClientBuilder).when(mockClientBuilder).serviceUrl(anyString());
        when(TopicName.get(any())).thenReturn(topicName);
        when(msg.getMessageId()).thenReturn(msgId);
        doReturn(mockClient).when(mockClientBuilder).build();

        Properties properties = new Properties();

        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Collections.singletonList("pulsar://localhost:6650"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "my-subscription-name");


        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumerSpy =
                spy(new PulsarKafkaConsumer<>(properties, new IntegerDeserializer(), new StringDeserializer()));

        doNothing().when(pulsarKafkaConsumerSpy).seekToEnd(anyCollection());
        PowerMockito.whenNew(PulsarKafkaConsumer.class).withAnyArguments().thenReturn(pulsarKafkaConsumerSpy);

        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumer =
                new PulsarKafkaConsumer<>(properties, new IntegerDeserializer(), new StringDeserializer());

        pulsarKafkaConsumer.poll(100);
        pulsarKafkaConsumer.close();
    }
}




