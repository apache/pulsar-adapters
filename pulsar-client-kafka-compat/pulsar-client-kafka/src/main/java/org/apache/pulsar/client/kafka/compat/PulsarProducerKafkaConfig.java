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
package org.apache.pulsar.client.kafka.compat;

import java.util.Properties;
import java.util.Set;

import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException.ProducerQueueIsFullError;

public class PulsarProducerKafkaConfig {

    /// Config variables
    public static final String PRODUCER_NAME = "pulsar.producer.name";
    public static final String INITIAL_SEQUENCE_ID = "pulsar.producer.initial.sequence.id";

    public static final String MAX_PENDING_MESSAGES = "pulsar.producer.max.pending.messages";
    public static final String MAX_PENDING_MESSAGES_ACROSS_PARTITIONS = "pulsar.producer.max.pending.messages.across.partitions";
    public static final String BATCHING_ENABLED = "pulsar.producer.batching.enabled";
    public static final String BATCHING_MAX_MESSAGES = "pulsar.producer.batching.max.messages";
    public static final String AUTO_UPDATE_PARTITIONS = "pulsar.auto.update.partitions";
    public static final String AUTO_UPDATE_PARTITIONS_REFRESH_DURATION = "pulsar.auto.update.partition.duration.ms";
    public static final String CRYPTO_READER_FACTORY_CLASS_NAME = "pulsar.crypto.reader.factory.class.name";
    /**
     * send operations will immediately fail with {@link ProducerQueueIsFullError} when there is no space left in
     * pending queue.
     **/
    public static final String BLOCK_IF_PRODUCER_QUEUE_FULL = "pulsar.block.if.producer.queue.full";

    public static ProducerBuilder<byte[]> getProducerBuilder(PulsarClient client, Properties properties) {
        ProducerBuilder<byte[]> producerBuilder = client.newProducer();

        if (properties.containsKey(PRODUCER_NAME)) {
            producerBuilder.producerName(properties.getProperty(PRODUCER_NAME));
        }

        if (properties.containsKey(INITIAL_SEQUENCE_ID)) {
            producerBuilder.initialSequenceId(Long.parseLong(properties.getProperty(INITIAL_SEQUENCE_ID)));
        }

        if (properties.containsKey(MAX_PENDING_MESSAGES)) {
            producerBuilder.maxPendingMessages(Integer.parseInt(properties.getProperty(MAX_PENDING_MESSAGES)));
        }

        if (properties.containsKey(MAX_PENDING_MESSAGES_ACROSS_PARTITIONS)) {
            producerBuilder.maxPendingMessagesAcrossPartitions(
                    Integer.parseInt(properties.getProperty(MAX_PENDING_MESSAGES_ACROSS_PARTITIONS)));
        }

        producerBuilder.enableBatching(Boolean.parseBoolean(properties.getProperty(BATCHING_ENABLED, "true")));

        if (properties.containsKey(BATCHING_MAX_MESSAGES)) {
            producerBuilder.batchingMaxMessages(Integer.parseInt(properties.getProperty(BATCHING_MAX_MESSAGES)));
        }

        if (properties.containsKey(AUTO_UPDATE_PARTITIONS)) {
            producerBuilder.autoUpdatePartitions(Boolean.parseBoolean(properties.getProperty(AUTO_UPDATE_PARTITIONS)));
        }

        if (properties.containsKey(CRYPTO_READER_FACTORY_CLASS_NAME)) {
            try {
                CryptoKeyReaderFactory cryptoReaderFactory = (CryptoKeyReaderFactory) Class
                        .forName(properties.getProperty(CRYPTO_READER_FACTORY_CLASS_NAME)).newInstance();
                producerBuilder.cryptoKeyReader(cryptoReaderFactory.create(properties));
                Set<String> keys = cryptoReaderFactory.getEncryptionKey(properties);
                if (keys != null) {
                    keys.forEach(producerBuilder::addEncryptionKey);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to create crypto reader using factory "
                        + properties.getProperty(CRYPTO_READER_FACTORY_CLASS_NAME), e);
            }
        }
        return producerBuilder;
    }
}
