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

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.apache.pulsar.client.kafka.compat.KafkaMessageRouter;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.naming.TopicName;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PulsarKafkaConsumerTest {

    @Test
    public void testPulsarKafkaConsumerWithHeaders_noAck() throws Exception {
        Consumer consumer = Mockito.mock(Consumer.class);
        String topic = "topic";

        Mockito.when(Mockito.mock(TopicName.class).getPartitionedTopicName()).thenReturn(topic);
        Mockito.doReturn("topic").when(consumer).getTopic();

        MessageMetadata messageMetadata = new MessageMetadata();
        messageMetadata.setPublishTime(System.currentTimeMillis());

        Map<String, String> headerMap = new HashMap<>();
        String kafkaHeaderKey = MessageConstants.KAFKA_MESSAGE_HEADER_PREFIX + "header1";
        String kafkaHeaderValue = Hex.encodeHexString(kafkaHeaderKey.getBytes());
        headerMap.put(kafkaHeaderKey, kafkaHeaderValue);
        headerMap.put(KafkaMessageRouter.PARTITION_ID, "0");
        Message<byte[]> msg = new MessageImpl<>(
                topic,
                "1:1",
                headerMap,
                "string".getBytes(),
                Schema.BYTES,
                messageMetadata
        );

        PulsarClient mockClient = Mockito.mock(PulsarClient.class);
        PulsarClientImpl mockClientImpl = Mockito.mock(PulsarClientImpl.class);

        CompletableFuture<Integer> mockNoOfPartitionFuture = CompletableFuture.completedFuture(1);

        ClientBuilder mockClientBuilder = Mockito.mock(ClientBuilder.class);
        Mockito.doReturn(mockClientBuilder).when(mockClientBuilder).serviceUrl(Mockito.anyString());
        Mockito.doReturn(mockClient).when(mockClientBuilder).build();

        Mockito.when(mockClientImpl.getNumberOfPartitions(Mockito.anyString())).thenReturn(mockNoOfPartitionFuture);

        Properties properties = new Properties();

        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Collections.singletonList("pulsar://localhost:6650"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "my-subscription-name");

        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumer =
                new PulsarKafkaConsumer<>(properties, new IntegerDeserializer(), new StringDeserializer());

        PulsarKafkaConsumer<Integer, String> pulsarKafkaConsumerSpy = Mockito.spy(pulsarKafkaConsumer);

        Mockito.doNothing().when(pulsarKafkaConsumerSpy).seekToEnd(anyCollection());

        pulsarKafkaConsumerSpy.received(consumer, msg);
        pulsarKafkaConsumerSpy.poll(100);
        pulsarKafkaConsumerSpy.close();

        Assert.assertEquals(kafkaHeaderValue, msg.getProperty(kafkaHeaderKey));
        Mockito.verify(pulsarKafkaConsumerSpy).seekToEnd(anyCollection());
        Mockito.verify(consumer, Mockito.times(0)).acknowledgeCumulativeAsync(Mockito.any(MessageId.class));
        Mockito.verify(Mockito.mock(Hex.class), Mockito.times(1)).decodeHex(Hex.encodeHexString(kafkaHeaderKey.getBytes()));
    }


}

