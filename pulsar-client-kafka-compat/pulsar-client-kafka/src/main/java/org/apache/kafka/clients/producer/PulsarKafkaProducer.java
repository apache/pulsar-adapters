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

import com.google.common.annotations.VisibleForTesting;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.commons.codec.binary.Hex;
import org.apache.kafka.clients.constants.MessageConstants;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.TypedMessageBuilderImpl;
import org.apache.pulsar.client.kafka.compat.KafkaMessageRouter;
import org.apache.pulsar.client.kafka.compat.KafkaProducerInterceptorWrapper;
import org.apache.pulsar.client.kafka.compat.PulsarClientKafkaConfig;
import org.apache.pulsar.client.kafka.compat.PulsarKafkaSchema;
import org.apache.pulsar.client.kafka.compat.PulsarProducerKafkaConfig;
import org.apache.pulsar.client.util.MessageIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PulsarKafkaProducer<K, V> implements Producer<K, V> {

    private static final Logger log = LoggerFactory.getLogger(PulsarKafkaProducer.class);
    private final PulsarClient client;
    final ProducerBuilder<byte[]> pulsarProducerBuilder;

    private final ConcurrentMap<String, org.apache.pulsar.client.api.Producer<byte[]>> producers = new ConcurrentHashMap<>();

    private final Schema<K> keySchema;
    private final Schema<V> valueSchema;

    private final Partitioner partitioner;
    private volatile Cluster cluster = Cluster.empty();
    @Getter
    private final int autoUpdatePartitionDurationMs;
    private final ScheduledExecutorService executor;

    private List<ProducerInterceptor<K, V>> interceptors;

    private final Properties properties;

    public PulsarKafkaProducer(Map<String, Object> configs) {
        this(new ProducerConfig(configs), null, null);
    }

    public PulsarKafkaProducer(Map<String, Object> configs, Serializer<K> keySerializer,
                               Serializer<V> valueSerializer) {
        this(new ProducerConfig(configs), new PulsarKafkaSchema<>(keySerializer), new PulsarKafkaSchema<>(valueSerializer));
    }

    public PulsarKafkaProducer(Map<String, Object> configs, Schema<K> keySchema, Schema<V> valueSchema) {
        this(new ProducerConfig(configs), keySchema, valueSchema);
    }

    public PulsarKafkaProducer(Properties properties) {
        this(new ProducerConfig(properties), null, null);
    }

    public PulsarKafkaProducer(Properties properties, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        this(new ProducerConfig(properties), new PulsarKafkaSchema<>(keySerializer), new PulsarKafkaSchema<>(valueSerializer));
    }

    public PulsarKafkaProducer(Properties properties, Schema<K> keySchema, Schema<V> valueSchema) {
        this(new ProducerConfig(properties), keySchema, valueSchema);
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    private PulsarKafkaProducer(ProducerConfig producerConfig, Schema<K> keySchema, Schema<V> valueSchema) {

        if (keySchema == null) {
            Serializer<K> kafkaKeySerializer = producerConfig.getConfiguredInstance(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serializer.class);
            kafkaKeySerializer.configure(producerConfig.originals(), true);
            this.keySchema = new PulsarKafkaSchema<>(kafkaKeySerializer);
        } else {
            this.keySchema = keySchema;
            producerConfig.ignore(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        }

        if (valueSchema == null) {
            Serializer<V> kafkaValueSerializer = producerConfig.getConfiguredInstance(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Serializer.class);
            kafkaValueSerializer.configure(producerConfig.originals(), false);
            this.valueSchema = new PulsarKafkaSchema<>(kafkaValueSerializer);
        } else {
            this.valueSchema = valueSchema;
            producerConfig.ignore(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        }

        partitioner = producerConfig.getConfiguredInstance(ProducerConfig.PARTITIONER_CLASS_CONFIG, Partitioner.class);
        partitioner.configure(producerConfig.originals());

        this.properties = new Properties();
        producerConfig.originals().forEach((k, v) -> properties.put(k, v));

        long keepAliveIntervalMs = Long.parseLong(properties.getProperty(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, "30000"));

        String serviceUrl = producerConfig.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG).get(0);
        try {
            // Support Kafka's ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG in ms.
            // If passed in value is greater than Integer.MAX_VALUE in second will throw IllegalArgumentException.
            int keepAliveInterval = Math.toIntExact(keepAliveIntervalMs / 1000);
            client = PulsarClientKafkaConfig.getClientBuilder(properties).serviceUrl(serviceUrl).keepAliveInterval(keepAliveInterval, TimeUnit.SECONDS).build();
        } catch (ArithmeticException e) {
            String errorMessage = String.format("Invalid value %d for 'connections.max.idle.ms'. Please use a value smaller than %d000 milliseconds.", keepAliveIntervalMs, Integer.MAX_VALUE);
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        } catch (PulsarClientException e) {
            throw new RuntimeException(e);
        }

        pulsarProducerBuilder = PulsarProducerKafkaConfig.getProducerBuilder(client, properties);

        // To mimic the same batching mode as Kafka, we need to wait a very little amount of
        // time to batch if the client is trying to send messages fast enough
        long lingerMs = Long.parseLong(properties.getProperty(ProducerConfig.LINGER_MS_CONFIG, "1"));
        pulsarProducerBuilder.batchingMaxPublishDelay(lingerMs, TimeUnit.MILLISECONDS);

        String compressionType = properties.getProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG);
        if ("gzip".equals(compressionType)) {
            pulsarProducerBuilder.compressionType(CompressionType.ZLIB);
        } else if ("lz4".equals(compressionType)) {
            pulsarProducerBuilder.compressionType(CompressionType.LZ4);
        }

        pulsarProducerBuilder.messageRouter(new KafkaMessageRouter(lingerMs));

        int sendTimeoutMillis = Integer.parseInt(properties.getProperty(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000"));
        pulsarProducerBuilder.sendTimeout(sendTimeoutMillis, TimeUnit.MILLISECONDS);

        // Kafka blocking semantic when blockOnBufferFull=false is different from Pulsar client
        // Pulsar throws error immediately when the queue is full and blockIfQueueFull=false
        // Kafka, on the other hand, still blocks for "max.block.ms" time and then gives error.
        Boolean sendTimeOutConfigured = sendTimeoutMillis > 0;
        boolean shouldBlockPulsarProducer = Boolean.parseBoolean(properties
                .getProperty(PulsarProducerKafkaConfig.BLOCK_IF_PRODUCER_QUEUE_FULL, sendTimeOutConfigured.toString()));
        pulsarProducerBuilder.blockIfQueueFull(shouldBlockPulsarProducer);

        interceptors = (List) producerConfig.getConfiguredInstances(
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, ProducerInterceptor.class);

        autoUpdatePartitionDurationMs = Integer.parseInt(
                properties.getProperty(PulsarProducerKafkaConfig.AUTO_UPDATE_PARTITIONS_REFRESH_DURATION, "300000"));
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void initTransactions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void beginTransaction() throws ProducerFencedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> map, String s) throws ProducerFencedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> map, ConsumerGroupMetadata consumerGroupMetadata) throws ProducerFencedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commitTransaction() throws ProducerFencedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abortTransaction() throws ProducerFencedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
        return send(record, null);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        org.apache.pulsar.client.api.Producer<byte[]> producer;

        try {
            producer = producers.computeIfAbsent(record.topic(), topic -> createNewProducer(topic));
        } catch (Exception e) {
            if (callback != null) {
                callback.onCompletion(null, e);
            }
            CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }

        TypedMessageBuilder<byte[]> messageBuilder = producer.newMessage();
        int messageSize = buildMessage(messageBuilder, record);

        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        messageBuilder.sendAsync().thenAccept((messageId) -> {
            future.complete(getRecordMetadata(record.topic(), messageBuilder, messageId, messageSize));
        }).exceptionally(ex -> {
            future.completeExceptionally(ex);
            return null;
        });

        future.handle((recordMetadata, throwable) -> {
            if (callback != null) {
                Exception exception = throwable != null ? new Exception(throwable) : null;
                callback.onCompletion(recordMetadata, exception);
            }
            return null;
        });

        return future;
    }

    @Override
    public void flush() {
        producers.values().stream()
                .map(p -> p.flushAsync())
                .collect(Collectors.toList())
                .forEach(CompletableFuture::join);
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        return Collections.emptyMap();
    }

    @Override
    public void close() {
        close(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        partitioner.close();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public void close(long timeout, TimeUnit unit) {
        if (client != null) {
            try {
                client.closeAsync().get(timeout, unit);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close(Duration duration) {
        close(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private org.apache.pulsar.client.api.Producer<byte[]> createNewProducer(String topic) {
        try {
            Map<TopicPartition, PartitionInfo> partitionMap = readPartitionsInfo(topic);
            // Add the partitions info for the new topic
            synchronized (this) {
                cluster = cluster.withPartitions(partitionMap);
            }
            if (getPartitions(topic) > 1 && autoUpdatePartitionDurationMs > 0) {
                // allow pulsar client lib to update partition before updating at kakfa clusters
                scheduleRefreshPartitionMetadata(topic, autoUpdatePartitionDurationMs * 2);
            }

            List<org.apache.pulsar.client.api.ProducerInterceptor> wrappedInterceptors = interceptors.stream()
                    .map(interceptor -> new KafkaProducerInterceptorWrapper(interceptor, keySchema, valueSchema, topic))
                    .collect(Collectors.toList());
            return pulsarProducerBuilder.clone().topic(topic)
                    .intercept(wrappedInterceptors
                            .toArray(new org.apache.pulsar.client.api.ProducerInterceptor[wrappedInterceptors.size()]))
                    .autoUpdatePartitionsInterval((int) autoUpdatePartitionDurationMs, TimeUnit.MILLISECONDS) 
                    .create();
        } catch (PulsarClientException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    public void scheduleRefreshPartitionMetadata(String topic, long autoUpdatePartitionDurationMs) {
        executor.scheduleAtFixedRate(() -> {
            int partitions = getPartitions(topic);
            Map<TopicPartition, PartitionInfo> partitionMap = readPartitionsInfo(topic);
            if (partitionMap.size() != partitions) {
                synchronized (this) {
                    cluster = cluster.withPartitions(partitionMap);
                }
                log.info("Updated partitions {} -> {} for topic {}", partitionMap.size(), partitions, topic);
            }
        }, autoUpdatePartitionDurationMs, autoUpdatePartitionDurationMs, TimeUnit.MILLISECONDS);
    }

    public Map<TopicPartition, PartitionInfo> readPartitionsInfo(String topic) {
        List<String> partitions = client.getPartitionsForTopic(topic).join();

        Map<TopicPartition, PartitionInfo> partitionsInfo = new HashMap<>();

        for (int i = 0; i < partitions.size(); i++) {
            TopicPartition tp = new TopicPartition(topic, i);
            PartitionInfo pi = new PartitionInfo(topic, i, null, null, null);
            partitionsInfo.put(tp, pi);
        }

        return partitionsInfo;
    }

    private int buildMessage(TypedMessageBuilder<byte[]> builder, ProducerRecord<K, V> record) {
        byte[] keyBytes = null;
        if (record.key() != null) {
            String key = getKey(record.topic(), record.key());
            keyBytes = key.getBytes(StandardCharsets.UTF_8);
            builder.key(key);
        }

        if (record.timestamp() != null) {
            builder.eventTime(record.timestamp());
        }

        if (valueSchema instanceof PulsarKafkaSchema) {
            ((PulsarKafkaSchema<V>) valueSchema).setTopic(record.topic());
        }
        byte[] value = valueSchema.encode(record.value());
        builder.value(value);

        if (record.partition() != null) {
            // Partition was explicitly set on the record
            builder.property(KafkaMessageRouter.PARTITION_ID, record.partition().toString());
        } else {
            // Get the partition id from the partitioner
            int partition = partitioner.partition(record.topic(), record.key(), keyBytes, record.value(), value, cluster);
            builder.property(KafkaMessageRouter.PARTITION_ID, Integer.toString(partition));
        }

        if (record.headers() != null) {
            record.headers().forEach(header -> {
                String key = MessageConstants.KAFKA_MESSAGE_HEADER_PREFIX + header.key();
                builder.property(key, Hex.encodeHexString(header.value()));
                log.debug("Formatted Kafka Specific Headers Before : {}, After : {}", header.key(), key);
            });
        }

        return value.length;
    }

    private String getKey(String topic, K key) {
        // If key is a String, we can use it as it is, otherwise, serialize to byte[] and encode in base64
        if (key instanceof String) {
            return (String) key;
        }
        if (keySchema instanceof PulsarKafkaSchema) {
            ((PulsarKafkaSchema) keySchema).setTopic(topic);
        }
        byte[] keyBytes = keySchema.encode(key);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    private RecordMetadata getRecordMetadata(String topic, TypedMessageBuilder<byte[]> msgBuilder, MessageId messageId,
            int size) {
        MessageIdImpl msgId = (MessageIdImpl) messageId;

        // Combine ledger id and entry id to form offset
        long offset = MessageIdUtils.getOffset(msgId);
        int partition = msgId.getPartitionIndex();

        TopicPartition tp = new TopicPartition(topic, partition);
        TypedMessageBuilderImpl<byte[]> mb = (TypedMessageBuilderImpl<byte[]>) msgBuilder;

        long publishTime = 0L;
        try {
            // there is no hasPublishTime() currently
            publishTime = mb.getPublishTime();
        } catch (IllegalStateException ise) {
            logger.debug("could not get publish time");
        }
        return new RecordMetadata(tp, offset, 0L, publishTime, 0L, mb.hasKey() ? mb.getKey().length() : 0, size);
    }

    public int getPartitions(String topic) {
        Integer partitions = cluster.partitionCountForTopic(topic);
        return partitions != null ? partitions : 0;
    }

    private static final Logger logger = LoggerFactory.getLogger(PulsarKafkaProducer.class);
}
