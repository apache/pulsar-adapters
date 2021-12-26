package org.apache.pulsar.spark

import org.apache.pulsar.client.api.MessageId

// Wrapper class for PubSub Message since Message is not Serializable
case class SparkPulsarMessage(data: Array[Byte], key: String, messageId: MessageId, publishTime: Long, eventTime: Long, topicName: String, properties: Map[String, String]) extends Serializable

