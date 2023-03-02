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

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.internals.PartitionAssignorAdapter;
import org.apache.kafka.common.TopicPartition;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mostly noop implementation of coordinator to include minimal functionality that
 * Kafka Streams expect to call all the callbacks.
 * Unlike in Kafka Streams, it does not actually coordinate anything,
 * named to keep lose reference to where that functionality is in KS.
 */
@Slf4j
public class PulsarConsumerCoordinator {

    // Used to encode "task assignment info" to force
    // kafka streams to create new tasks after subscription
    private static class AssignmentInfo {
        // v.2 is the first to support partitions per host
        // the rest is not applicable/useful but requires more placeholder data encoded
        private final int usedVersion = 2;
        private final List<TopicPartition> partitions;

        public AssignmentInfo(final List<TopicPartition> partitions) {
            this.partitions = partitions;
        }

        @SneakyThrows
        public ByteBuffer encode() {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();

            try (final DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(usedVersion); // version
                encodeActiveAndStandbyTaskAssignment(out, partitions);
                encodePartitionsByHost(out);

                out.flush();
                out.close();

                return ByteBuffer.wrap(baos.toByteArray());
            }
        }

        private void encodeActiveAndStandbyTaskAssignment(final DataOutputStream out,
                                                          final List<TopicPartition> partitions) throws IOException {

            int lastId = 0;
            final Map<String, Integer> topicGroupIds = new HashMap<>();
            // encode active tasks
            // the number of assigned partitions must be the same as number of active tasks
            out.writeInt(partitions.size());
            for (TopicPartition p : partitions) {
                final int topicGroupId;
                if (topicGroupIds.containsKey(p.topic())) {
                    topicGroupId = topicGroupIds.get(p.topic());
                } else {
                    topicGroupId = lastId;
                    lastId++;
                    topicGroupIds.put(p.topic(), topicGroupId);
                }
                out.writeInt(topicGroupId);
                out.writeInt(p.partition());
            }

            // encode standby tasks
            out.writeInt(0);
        }

        private void encodePartitionsByHost(final DataOutputStream out) throws IOException {
            // encode partitions by host
            out.writeInt(1);
            writeHostInfo(out, "fakeHost", 9999);
            writeTopicPartitions(out, partitions);
        }

        private void writeHostInfo(final DataOutputStream out, String host, int port) throws IOException {
            out.writeUTF(host);
            out.writeInt(port);
        }

        private void writeTopicPartitions(final DataOutputStream out,
                                          final List<TopicPartition> partitions) throws IOException {
            out.writeInt(partitions.size());
            for (final TopicPartition partition : partitions) {
                out.writeUTF(partition.topic());
                out.writeInt(partition.partition());
            }
        }
    }

    public static void invokePartitionsAssigned(final String groupId, final ConsumerConfig config, final List<TopicPartition> assignedPartitions) {
        final List<ConsumerPartitionAssignor> assignors = PartitionAssignorAdapter.getAssignorInstances(
                config.getList(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG),
                config.originals());

        // Give the assignor a chance to update internal state based on the received assignment
        ConsumerGroupMetadata groupMetadata = new ConsumerGroupMetadata(groupId);

        ByteBuffer bbInfo = new AssignmentInfo(assignedPartitions).encode();
        ConsumerPartitionAssignor.Assignment assignment =
                new ConsumerPartitionAssignor.Assignment(assignedPartitions, bbInfo);

        for (ConsumerPartitionAssignor assignor : assignors) {
            assignor.onAssignment(assignment, groupMetadata);
        }
    }

}
