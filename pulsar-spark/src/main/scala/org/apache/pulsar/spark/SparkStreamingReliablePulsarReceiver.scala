package org.apache.pulsar.spark

import com.google.common.util.concurrent.RateLimiter
import org.apache.pulsar.client.api._
import org.apache.pulsar.client.impl.PulsarClientImpl
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.receiver.Receiver
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
  * Custom spark receiver to pull messages from Pubsub topic and push into reliable store.
  * If backpressure is enabled,the message ingestion rate for this receiver will be managed by Spark.
  *
  * Following spark configurations can be used to control rates and block size
  * <i>spark.streaming.backpressure.enabled</i>
  * <i>spark.streaming.backpressure.initialRate</i>
  * <i>spark.streaming.receiver.maxRate</i>
  * <i>spark.streaming.blockQueueSize</i>: Controlling block size
  * <i>spark.streaming.backpressure.pid.minRate</i>
  *
  * See Spark streaming configurations doc
  * <a href="https://spark.apache.org/docs/latest/configuration.html#spark-streaming</a>
  *
  * @param storageLevel              Storage level to be used
  * @param serviceUrl                Service URL to the Pubsub Cluster
  * @param pulsarConf                Map of PulsarConf containing keys from `org.apache.pulsar.client.impl.conf.ConsumerConfigurationData`
  * @param authentication            Authentication object for authenticating consumer to pulsar. Use `AuthenticationDisabled` is authentication is disabled.
  * @param sparkConf                 Spark configs
  * @param maxPollSize               Max number of records to read by consumer in one batch
  * @param consumerName              Consumer name to be used by receiver
  * @param autoAcknowledge           Acknowledge pubsub message or not
  * @param rateMultiplierFactor      Rate multiplier factor in case you want to have more rate than what PIDRateEstimator suggests. > 1.0  is useful in case
  *                                  of spark dynamic allocation to utilise extra resources. Keep it 1.0 if spark dynamic allocation is disabled.
  */

class SparkStreamingReliablePulsarReceiver(override val storageLevel: StorageLevel,
                                           val serviceUrl: String,
                                           val pulsarConf: Map[String, Any],
                                           val authentication: Authentication,
                                           val sparkConf: SparkConf,
                                           val maxPollSize: Int,
                                           val consumerName: String,
                                           val autoAcknowledge: Boolean = true,
                                           val rateMultiplierFactor: Double  = 1.0) extends Receiver[SparkPulsarMessage](storageLevel) {

  private val maxRateLimit: Long = sparkConf.getLong("spark.streaming.receiver.maxRate", Long.MaxValue)
  private val blockSize: Int = sparkConf.getInt("spark.streaming.blockQueueSize", maxPollSize)
  private val blockIntervalMs: Long = sparkConf.getTimeAsMs("spark.streaming.blockInterval", "200ms")

  lazy val rateLimiter: RateLimiter = RateLimiter.create(math.min(
    sparkConf.getLong("spark.streaming.backpressure.initialRate", maxRateLimit),
    maxRateLimit
  ).toDouble)

  val buffer: ListBuffer[SparkPulsarMessage] = new ListBuffer[SparkPulsarMessage]()

  private var pulsarClient: PulsarClient = null
  private var consumer: Consumer[Array[Byte]] = null

  var latestStorePushTime: Long = System.currentTimeMillis()

  override def onStart(): Unit = {
    try {
      pulsarClient = PulsarClient.builder.serviceUrl(serviceUrl).authentication(authentication).build
      val topics = pulsarConf.get("topicNames").get.asInstanceOf[List[String]]
      consumer = pulsarClient.asInstanceOf[PulsarClientImpl].newConsumer()
        .loadConf(pulsarConf.filter(_._1 != "topicNames").map(pay => pay._1 -> pay._2.asInstanceOf[AnyRef]))
        .topics(topics).receiverQueueSize(maxPollSize).consumerName(consumerName)
        .subscribe()
    } catch {
      case e: Exception =>
        SparkStreamingReliablePulsarReceiver.LOG.error("Failed to start subscription : {}", e.getMessage)
        e.printStackTrace()
        restart("Restart a consumer")
    }
    latestStorePushTime = System.currentTimeMillis()
    new Thread() {
      override def run() {
        receive()
      }
    }.start()

  }

  override def onStop(): Unit = try {
    if (consumer != null) consumer.close()
    if (pulsarClient != null) pulsarClient.close()
  } catch {
    case e: PulsarClientException =>
      SparkStreamingReliablePulsarReceiver.LOG.error("Failed to close client : {}", e.getMessage)
  }

  // Function that continuously keeps polling records from pulsar and store them.
  def receive(): Unit = {
    while(!isStopped()){
      try {
        val messages= consumer.batchReceive().toList

        // Update rate limit if necessary
        updateRateLimit

        if(messages.size() > 0){
          buffer ++= messages.map(msg => {
            SparkPulsarMessage(msg.getData, msg.getKey, msg.getMessageId, msg.getPublishTime, msg.getEventTime, msg.getTopicName, msg.getProperties.asScala.toMap)
          })

        }

        val timeSinceLastStorePush = System.currentTimeMillis() - latestStorePushTime

        // Push messages to spark store if `blockIntervalMs` has passed or we have more messages than blockSize
        if(timeSinceLastStorePush >= blockIntervalMs || buffer.length >= blockSize) {
          val (completeBlocks, partialBlock) = buffer.grouped(blockSize)
            .partition(block => block.size == blockSize)
          val blocksToStore = if (completeBlocks.hasNext) completeBlocks else partialBlock

          buffer.clear()

          if(completeBlocks.hasNext && partialBlock.hasNext)
            buffer.appendAll(partialBlock.next())

          while (blocksToStore.hasNext){
            val groupedMessages = blocksToStore.next().toList
            SparkStreamingReliablePulsarReceiver.LOG.debug("Pushing " +  groupedMessages.size + " messages in store.")
            rateLimiter.acquire(groupedMessages.size)
            store(groupedMessages.toIterator)
            if(autoAcknowledge)
              consumer.acknowledge(groupedMessages.map(_.messageId))
          }
          latestStorePushTime = System.currentTimeMillis()
        }

      }
      catch {
        case e: Exception => reportError("Failed to get messages", e)
          e.printStackTrace()
      }
    }
  }

  def updateRateLimit(): Unit = {
    val newRateLimit = rateMultiplierFactor * supervisor.getCurrentRateLimit.min(maxRateLimit)
    SparkStreamingReliablePulsarReceiver.LOG.info("New rate limit: " + newRateLimit)
    if (rateLimiter.getRate != newRateLimit) {
      rateLimiter.setRate(newRateLimit)
    }
  }

}

object SparkStreamingReliablePulsarReceiver {
  private val LOG = LoggerFactory.getLogger(classOf[SparkStreamingReliablePulsarReceiver])
}