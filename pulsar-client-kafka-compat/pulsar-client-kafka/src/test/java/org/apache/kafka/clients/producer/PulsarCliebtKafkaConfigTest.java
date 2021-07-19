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
package org.apache.kafka.clients.producer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.internals.DefaultPartitioner;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.CryptoKeyReader;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.ConsumerBuilderImpl;
import org.apache.pulsar.client.impl.DefaultCryptoKeyReader;
import org.apache.pulsar.client.impl.ProducerBuilderImpl;
import org.apache.pulsar.client.kafka.compat.CryptoKeyReaderFactory;
import org.apache.pulsar.client.kafka.compat.PulsarConsumerKafkaConfig;
import org.apache.pulsar.client.kafka.compat.PulsarProducerKafkaConfig;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.collect.Sets;

public class PulsarCliebtKafkaConfigTest {

  
    @Test
    public void testPulsarKafkaProducer() {
        ClientBuilder mockClientBuilder = mock(ClientBuilder.class);
        ProducerBuilder mockProducerBuilder = mock(ProducerBuilder.class);
        doAnswer(invocation -> {
            Assert.assertEquals((int)invocation.getArguments()[0], 1000000, "Send time out is suppose to be 1000.");
            return mockProducerBuilder;
        }).when(mockProducerBuilder).sendTimeout(anyInt(), any(TimeUnit.class));
        doReturn(mockClientBuilder).when(mockClientBuilder).serviceUrl(anyString());
        doAnswer(invocation -> {
            Assert.assertEquals((int)invocation.getArguments()[0], 1000, "Keep alive interval is suppose to be 1000.");
            return mockClientBuilder;
        }).when(mockClientBuilder).keepAliveInterval(anyInt(), any(TimeUnit.class));

        // validate producer
        Properties properties = new Properties();
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, DefaultPartitioner.class);
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Arrays.asList("pulsar://localhost:6650"));
        properties.put(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, "1000000");
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000000");
        properties.put(PulsarProducerKafkaConfig.CRYPTO_READER_FACTORY_CLASS_NAME, CryptoKeyReaderFactoryImpl.class.getName());
        PulsarKafkaProducer producer = new PulsarKafkaProducer<>(properties);
        ProducerBuilderImpl producerBuilder = (ProducerBuilderImpl) producer.pulsarProducerBuilder;
        assertEquals(producerBuilder.getConf().getCryptoKeyReader(), CryptoKeyReaderFactoryImpl.reader);
        assertEquals(producerBuilder.getConf().getEncryptionKeys(), CryptoKeyReaderFactoryImpl.encryptionKeys);

        // validate consumer
        properties = new Properties();
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Arrays.asList("pulsar://localhost:6650"));
        properties.put(PulsarProducerKafkaConfig.CRYPTO_READER_FACTORY_CLASS_NAME, CryptoKeyReaderFactoryImpl.class.getName());
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilderImpl<byte[]> consumerBuilder = new ConsumerBuilderImpl<>(null, null);
        doReturn(consumerBuilder).when(client).newConsumer();
        PulsarConsumerKafkaConfig.getConsumerBuilder(client , properties);
        assertEquals(consumerBuilder.getConf().getCryptoKeyReader(), CryptoKeyReaderFactoryImpl.reader);
    }

    public static class CryptoKeyReaderFactoryImpl implements CryptoKeyReaderFactory {
        static final CryptoKeyReader reader = DefaultCryptoKeyReader.builder().build();
        static final Set<String> encryptionKeys = Sets.newHashSet("test");

        @Override
        public CryptoKeyReader create(Properties properties) {
            return reader;
        }

        @Override
        public Set<String> getEncryptionKey(Properties properties) {
            return encryptionKeys;
        }
    }
}
