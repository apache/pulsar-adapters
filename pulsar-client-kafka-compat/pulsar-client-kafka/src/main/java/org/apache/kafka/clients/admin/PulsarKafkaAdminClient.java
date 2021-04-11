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
package org.apache.kafka.clients.admin;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionReplica;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaFilter;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.kafka.compat.PulsarClientKafkaConfig;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.functions.utils.FunctionCommon;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mostly noop implementation of AdminClient
 */
@Slf4j
public class PulsarKafkaAdminClient implements Admin {

    private final Properties properties;
    private final PulsarAdmin admin;

    private final ConcurrentHashMap<String, Boolean> partitionedTopics = new ConcurrentHashMap<>();

    private PulsarKafkaAdminClient(AdminClientConfig config) {
        properties = new Properties();
        log.info("config originals: {}", config.originals());
        config.originals().forEach((k, v) -> {
            log.info("Setting k = {} v = {}", k, v);
            // properties do not allow null values
            if (k != null && v != null) {
                properties.put(k, v);
            }
        });

        PulsarAdminBuilder builder = PulsarClientKafkaConfig.getAdminBuilder(properties);
        try {
            admin = builder.build();
        } catch (PulsarClientException e) {
            log.error("Could not create Pulsar Admin", e);
            throw new RuntimeException(e);
        }
    }

    static PulsarKafkaAdminClient createInternal(AdminClientConfig config) {
        return new PulsarKafkaAdminClient(config);
    }

    public static Admin create(Properties props) {
        return PulsarKafkaAdminClient.createInternal(new AdminClientConfig(props, true));
    }

    public static Admin create(Map<String, Object> conf) {
        return PulsarKafkaAdminClient.createInternal(new AdminClientConfig(conf, true));
    }

    private boolean isPartitionedTopic(String topic) {
        Boolean res = partitionedTopics.computeIfAbsent(topic, t -> {
            try {
                int numPartitions = admin.topics()
                        .getPartitionedTopicMetadata(t)
                        .partitions;
                return numPartitions > 0;
            } catch (PulsarAdminException e) {
                log.error("getPartitionedTopicMetadata failed", e);
                return null;
            }
        });
        if (res == null) {
            throw new RuntimeException("Could not get topic metadata");
        }
        return res;
    }

    public <K, V> Map<K, KafkaFutureImpl<V>> execute(Collection<K> param,
                                                     java.util.function.BiConsumer<K, KafkaFutureImpl<V>> func) {
        // preparing topics list for asking metadata about them
        final Map<K, KafkaFutureImpl<V>> futures
                = new HashMap<>();
        for (K value : param) {
            KafkaFutureImpl<V> future = new KafkaFutureImpl<>();
            futures.put(value, future);
            func.accept(value, future);
        }
        return futures;
    }

    public <K, K2, V> Map<K, KafkaFutureImpl<V>> execute(Map<K, K2> param,
                                 java.util.function.BiConsumer<Map.Entry<K, K2>, KafkaFutureImpl<V>> func) {
        // preparing topics list for asking metadata about them
        final Map<K, KafkaFutureImpl<V>> futures
                = new HashMap<>(param.size());
        for (Map.Entry<K, K2> value : param.entrySet()) {
            KafkaFutureImpl<V> future = new KafkaFutureImpl<>();
            futures.put(value.getKey(), future);
            func.accept(value, future);
        }
        return futures;
    }

    @Override
    public void close(Duration duration) {
        admin.close();
    }

    @Override
    public ListOffsetsResult listOffsets(Map<TopicPartition, OffsetSpec> topicPartitionOffsets,
                                         ListOffsetsOptions listOffsetsOptions) {
        final Map<TopicPartition, KafkaFutureImpl<ListOffsetsResult.ListOffsetsResultInfo>> futures =
            execute(topicPartitionOffsets, (entry, future) -> {
                TopicPartition topicPartition = entry.getKey();
                String topicName = isPartitionedTopic(topicPartition.topic())
                        ? topicPartition.topic() + TopicName.PARTITIONED_TOPIC_SUFFIX + topicPartition.partition()
                        : topicPartition.topic();
                admin.topics()
                        .getLastMessageIdAsync(topicName)
                        .whenComplete((msgId, ex) -> {
                            if (ex == null) {
                                long offset = FunctionCommon.getSequenceId(msgId);
                                future.complete(new ListOffsetsResult.ListOffsetsResultInfo(
                                        offset,
                                        System.currentTimeMillis(),
                                        Optional.empty()));
                            } else {
                                log.error("Admin failed to get lastMessageId for topic " + topicName, ex);
                                future.completeExceptionally(ex);
                            }
                        });
            });
        return new ListOffsetsResult(new HashMap<>(futures));
    }

    @Override
    public DeleteRecordsResult deleteRecords(Map<TopicPartition, RecordsToDelete> map, DeleteRecordsOptions deleteRecordsOptions) {
        final Map<TopicPartition, KafkaFutureImpl<DeletedRecords>> futures =
                execute(map, (entry, future) -> {
                    // nothing to do, cannot delete messages before offset, let pulsar expire stuff
                    future.complete(new DeletedRecords(entry.getValue().beforeOffset()));
                });

        return new DeleteRecordsResult(new HashMap<>(futures));
    }

    @Override
    public CreateTopicsResult createTopics(Collection<NewTopic> collection, CreateTopicsOptions createTopicsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DeleteTopicsResult deleteTopics(Collection<String> collection, DeleteTopicsOptions deleteTopicsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ListTopicsResult listTopics(ListTopicsOptions listTopicsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeTopicsResult describeTopics(Collection<String> collection, DescribeTopicsOptions describeTopicsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeClusterResult describeCluster(DescribeClusterOptions describeClusterOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeAclsResult describeAcls(AclBindingFilter aclBindingFilter, DescribeAclsOptions describeAclsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public CreateAclsResult createAcls(Collection<AclBinding> collection, CreateAclsOptions createAclsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DeleteAclsResult deleteAcls(Collection<AclBindingFilter> collection, DeleteAclsOptions deleteAclsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeConfigsResult describeConfigs(Collection<ConfigResource> collection, DescribeConfigsOptions describeConfigsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterConfigsResult alterConfigs(Map<ConfigResource, Config> map, AlterConfigsOptions alterConfigsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterConfigsResult incrementalAlterConfigs(Map<ConfigResource, Collection<AlterConfigOp>> map, AlterConfigsOptions alterConfigsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterReplicaLogDirsResult alterReplicaLogDirs(Map<TopicPartitionReplica, String> map, AlterReplicaLogDirsOptions alterReplicaLogDirsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeLogDirsResult describeLogDirs(Collection<Integer> collection, DescribeLogDirsOptions describeLogDirsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeReplicaLogDirsResult describeReplicaLogDirs(Collection<TopicPartitionReplica> collection, DescribeReplicaLogDirsOptions describeReplicaLogDirsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public CreatePartitionsResult createPartitions(Map<String, NewPartitions> map, CreatePartitionsOptions createPartitionsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public CreateDelegationTokenResult createDelegationToken(CreateDelegationTokenOptions createDelegationTokenOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public RenewDelegationTokenResult renewDelegationToken(byte[] bytes, RenewDelegationTokenOptions renewDelegationTokenOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ExpireDelegationTokenResult expireDelegationToken(byte[] bytes, ExpireDelegationTokenOptions expireDelegationTokenOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeDelegationTokenResult describeDelegationToken(DescribeDelegationTokenOptions describeDelegationTokenOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeConsumerGroupsResult describeConsumerGroups(Collection<String> collection, DescribeConsumerGroupsOptions describeConsumerGroupsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ListConsumerGroupsResult listConsumerGroups(ListConsumerGroupsOptions listConsumerGroupsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ListConsumerGroupOffsetsResult listConsumerGroupOffsets(String s, ListConsumerGroupOffsetsOptions listConsumerGroupOffsetsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DeleteConsumerGroupsResult deleteConsumerGroups(Collection<String> collection, DeleteConsumerGroupsOptions deleteConsumerGroupsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DeleteConsumerGroupOffsetsResult deleteConsumerGroupOffsets(String s, Set<TopicPartition> set, DeleteConsumerGroupOffsetsOptions deleteConsumerGroupOffsetsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ElectLeadersResult electLeaders(ElectionType electionType, Set<TopicPartition> set, ElectLeadersOptions electLeadersOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterPartitionReassignmentsResult alterPartitionReassignments(Map<TopicPartition, Optional<NewPartitionReassignment>> map, AlterPartitionReassignmentsOptions alterPartitionReassignmentsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public ListPartitionReassignmentsResult listPartitionReassignments(Optional<Set<TopicPartition>> optional, ListPartitionReassignmentsOptions listPartitionReassignmentsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public RemoveMembersFromConsumerGroupResult removeMembersFromConsumerGroup(String s, RemoveMembersFromConsumerGroupOptions removeMembersFromConsumerGroupOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterConsumerGroupOffsetsResult alterConsumerGroupOffsets(String s, Map<TopicPartition, OffsetAndMetadata> map, AlterConsumerGroupOffsetsOptions alterConsumerGroupOffsetsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeClientQuotasResult describeClientQuotas(ClientQuotaFilter clientQuotaFilter, DescribeClientQuotasOptions describeClientQuotasOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterClientQuotasResult alterClientQuotas(Collection<ClientQuotaAlteration> collection, AlterClientQuotasOptions alterClientQuotasOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeUserScramCredentialsResult describeUserScramCredentials(List<String> list, DescribeUserScramCredentialsOptions describeUserScramCredentialsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public AlterUserScramCredentialsResult alterUserScramCredentials(List<UserScramCredentialAlteration> list, AlterUserScramCredentialsOptions alterUserScramCredentialsOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public DescribeFeaturesResult describeFeatures(DescribeFeaturesOptions describeFeaturesOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public UpdateFeaturesResult updateFeatures(Map<String, FeatureUpdate> map, UpdateFeaturesOptions updateFeaturesOptions) {
        throw new UnsupportedOperationException("Operation is not supported");
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() {
        throw new UnsupportedOperationException("Operation is not supported");
    }
}
