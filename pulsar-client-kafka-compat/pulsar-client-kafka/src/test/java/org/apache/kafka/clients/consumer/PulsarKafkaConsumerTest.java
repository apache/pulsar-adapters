/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.ConsumerImpl;
import org.apache.pulsar.client.impl.MultiTopicsConsumerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.TopicMessageIdImpl;
import org.apache.pulsar.client.kafka.compat.PulsarClientKafkaConfig;
import org.apache.pulsar.client.kafka.compat.PulsarConsumerKafkaConfig;
import org.apache.pulsar.client.util.MessageIdUtils;
import org.apache.pulsar.common.naming.TopicName;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.IObjectFactory;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@PrepareForTest({PulsarClientKafkaConfig.class, PulsarConsumerKafkaConfig.class})
@PowerMockIgnore({"org.apache.logging.log4j.*", "org.apache.kafka.clients.consumer.ConsumerInterceptor"})
public class PulsarKafkaConsumerTest {

    @ObjectFactory
    // Necessary to make PowerMockito.mockStatic work with TestNG.
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    @Test
    public void testCommitSync() throws PulsarClientException {

        String topic = "persistent://prop/ns/t1";

        ClientBuilder mockClientBuilder = mock(ClientBuilder.class);
        ConsumerBuilder mockConsumerBuilder = mock(ConsumerBuilder.class);
        PulsarClientImpl mockPulsarClient = mock(PulsarClientImpl.class);
        MultiTopicsConsumerImpl mockMultiTopicsConsumerImpl = mock(MultiTopicsConsumerImpl.class);
        ConsumerImpl mockConsumerImpl = mock(ConsumerImpl.class);

        doReturn(mockClientBuilder).when(mockClientBuilder).serviceUrl(anyString());

        PowerMockito.mockStatic(PulsarClientKafkaConfig.class);
        PowerMockito.mockStatic(PulsarConsumerKafkaConfig.class);

        when(PulsarClientKafkaConfig.getClientBuilder(any(Properties.class))).thenReturn(mockClientBuilder);
        when(PulsarConsumerKafkaConfig.getConsumerBuilder(any(), any())).thenReturn(mockConsumerBuilder);
        doReturn(mockPulsarClient).when(mockClientBuilder).build();
        doReturn(mockConsumerBuilder).when(mockConsumerBuilder).clone();
        doReturn(mockConsumerBuilder).when(mockConsumerBuilder).topic(any());


        CompletableFuture<Void> voidFuture = new CompletableFuture<>();
        voidFuture.complete(null);
        doReturn(voidFuture).when(mockMultiTopicsConsumerImpl).acknowledgeCumulativeAsync(any(MessageId.class));
        doReturn(voidFuture).when(mockConsumerImpl).acknowledgeCumulativeAsync(any(MessageId.class));

        Properties properties = new Properties();
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Collections.singletonList("pulsar://localhost:6650"));

        List<Integer> partitionCounts = Arrays.asList(0, 2);

        for (Integer count : partitionCounts) {

            CompletableFuture mockConsumerFuture = new CompletableFuture();
            mockConsumerFuture.complete(isTopicPartitioned(count) ? mockMultiTopicsConsumerImpl : mockConsumerImpl);
            doReturn(mockConsumerFuture).when(mockConsumerBuilder).subscribeAsync();

            CompletableFuture mockPartitionFuture = new CompletableFuture();
            mockPartitionFuture.complete(count);
            doReturn(mockPartitionFuture).when(mockPulsarClient).getNumberOfPartitions(anyString());

            Consumer<String, String> consumer = spy(new PulsarKafkaConsumer<>(properties));

            Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = getRecords(count, topic);
            ConsumerRecords<String, String> mockRecords = new ConsumerRecords<>(recordMap);
            doReturn(mockRecords).when(consumer).poll(any());

            consumer.subscribe(Collections.singletonList(topic));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            records.partitions().forEach(partition -> {
                List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
                long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
                offsets.put(partition, new OffsetAndMetadata(lastOffset + 1));
            });
            consumer.commitSync(offsets);

            if (isTopicPartitioned(count)) {
                for (Map.Entry<TopicPartition, List<ConsumerRecord<String, String>>> record : recordMap.entrySet()) {
                    MessageId msgId = MessageIdUtils.getMessageId(record.getValue().get(0).offset() + 1);
                    String partitionName = TopicName.get(record.getKey().topic()).getPartition(record.getKey().partition()).toString();
                    TopicMessageIdImpl topicMessageId = new TopicMessageIdImpl(partitionName, record.getKey().topic(), msgId);
                    verify(mockMultiTopicsConsumerImpl, times(1)).acknowledgeCumulativeAsync(topicMessageId);
                }
            } else {
                verify(mockConsumerImpl, times(records.partitions().size())).acknowledgeCumulativeAsync(any(MessageId.class));
            }
        }
    }

    private boolean isTopicPartitioned(int partitionsCount) {
        return partitionsCount > 0;
    }

    private Map<TopicPartition, List<ConsumerRecord<String, String>>> getRecords(int partitionsCount, String topic) {
        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(new TopicPartition(topic, 0), Collections.singletonList(new ConsumerRecord<>(topic, 0, 10L, "1", "1")));
        for (int i = 1; i < partitionsCount; i++) {
            recordMap.put(new TopicPartition(topic, 1), Collections.singletonList(new ConsumerRecord<>(topic, 1, 20L, "2", "1")));
        }
        return recordMap;
    }
}
