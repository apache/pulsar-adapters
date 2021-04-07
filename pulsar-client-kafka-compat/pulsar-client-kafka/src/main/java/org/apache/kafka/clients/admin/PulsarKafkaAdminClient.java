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

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionReplica;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaFilter;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Mostly noop implementation of AdminClient
 * to avoid Kafka Streams trying to use the Kafka one/trying to connect to kafka.
 * Also to override static methods.
 */
public class PulsarKafkaAdminClient implements Admin {

    private PulsarKafkaAdminClient(AdminClientConfig config, KafkaAdminClient.TimeoutProcessorFactory timeoutProcessorFactory) {
    }

    static PulsarKafkaAdminClient createInternal(AdminClientConfig config, KafkaAdminClient.TimeoutProcessorFactory timeoutProcessorFactory) {
        return new PulsarKafkaAdminClient(config, timeoutProcessorFactory);
    }

    public static Admin create(Properties props) {
        return PulsarKafkaAdminClient.createInternal(new AdminClientConfig(props, true), (KafkaAdminClient.TimeoutProcessorFactory)null);
    }

    public static Admin create(Map<String, Object> conf) {
        return PulsarKafkaAdminClient.createInternal(new AdminClientConfig(conf, true), (KafkaAdminClient.TimeoutProcessorFactory)null);
    }

    @Override
    public void close(Duration duration) {
        // noop
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
    public DeleteRecordsResult deleteRecords(Map<TopicPartition, RecordsToDelete> map, DeleteRecordsOptions deleteRecordsOptions) {
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
    public ListOffsetsResult listOffsets(Map<TopicPartition, OffsetSpec> map, ListOffsetsOptions listOffsetsOptions) {
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
