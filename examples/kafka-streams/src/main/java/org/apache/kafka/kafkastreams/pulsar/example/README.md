<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

## Apache Kafka Streams for Pulsar

This page describes how to use the [Kafka Streams](https://kafka.apache.org/27/documentation/streams/) with Pulsar topics.

## Example

### LineSplit

This Kafka Streams job is consuming from a Pulsar topic, splitting lines into words in a streaming fashion. 
The job writes the words to another Pulsar topic.

The steps to run the example:

1. Start Pulsar Standalone.

   You can follow the [instructions](https://pulsar.apache.org/docs/en/standalone/) to start a Pulsar standalone locally.

    ```shell
    $ bin/pulsar standalone
    ```

2. Create topics to use later. The easiest way to do so is to produce a message to the topic:

   ```shell
   $ bin/pulsar-client produce streams-plaintext-input --messages "tada"
   $ bin/pulsar-client produce streams-linesplit-output --messages "tada"
   ```

3. Build the examples.

    ```shell
    $ cd ${PULSAR_ADAPTORS_HOME}/examples/kafka-streams
    $ mvn clean package
    ```

4. Run the example.

    ```shell
    $ mvn exec:java -Dexec.mainClass=org.apache.kafka.kafkastreams.pulsar.example.LineSplit
    ```

5. Produce messages to topic `streams-plaintext-input`.

    ```shell
    $ bin/pulsar-client produce streams-plaintext-input --messages "I produced a few words"
    ```

6. You can check that consumer sees the words in output topic `streams-linesplit-output`, e.g.:

    ```shell
    $ bin/pulsar-client consume streams-linesplit-output -s test -p Earliest -n 0  
    ```
   outputs something like
   ```text
   ----- got message -----
   key:[null], properties:[pulsar.partition.id=0], content:I
   ----- got message -----
   key:[null], properties:[pulsar.partition.id=0], content:produced
   ----- got message -----
   key:[null], properties:[pulsar.partition.id=0], content:a
   ----- got message -----
   key:[null], properties:[pulsar.partition.id=0], content:few
   ----- got message -----
   key:[null], properties:[pulsar.partition.id=0], content:words
   ```

### WordCount

This Kafka Streams job is consuming from a Pulsar topic, splitting lines into words in a streaming fashion.
The job writes the words and counts to another Pulsar topic.

The steps to run the example:

1. Start Pulsar Standalone.

   You can follow the [instructions](https://pulsar.apache.org/docs/en/standalone/) to start a Pulsar standalone locally.

    ```shell
    $ bin/pulsar standalone
    ```

2. Build the examples.

    ```shell
    $ cd ${PULSAR_ADAPTORS_HOME}/examples/kafka-streams
    $ mvn clean package
    ```

3. Run the example.

    ```shell
    $ mvn exec:java -Dexec.mainClass=org.apache.kafka.kafkastreams.pulsar.example.WordCount
    ```

6. Produce messages to topic `streams-plaintext-input`.

    ```shell
    $ bin/pulsar-client produce streams-plaintext-input --messages "a a a a b c"
    ```

6. You can check that consumer sees the words in output topic `streams-wordcount-output`, e.g.:

    ```shell
    $ bin/pulsar-client consume streams-wordcount-output -s test -p Earliest -n 0  
    ```
   outputs something like
   ```text
   ----- got message -----
   key:[YQ==], properties:[pulsar.partition.id=0], content:
   ----- got message -----
   key:[Yg==], properties:[pulsar.partition.id=0], content:
   ----- got message -----
   key:[Yw==], properties:[pulsar.partition.id=0], content:
   ```
   (not sure how to force it to decode String key and Long value from byte[]/byte[] topic)
