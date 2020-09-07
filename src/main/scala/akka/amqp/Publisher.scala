package akka.amqp

import java.util.{Collections, TreeSet}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import akka.actor._
import ChannelActor._

case class Message(
    payload: AnyRef,
    routingKey: String,
    mandatory: Boolean = false,
    immediate: Boolean = false,
    properties: Option[BasicProperties] = None
)
//sealed trait PublishMode
//case object Immediate extends PublishMode
//case object Confirm extends PublishMode
//case object Normal extends PublishMode
case class PublishToExchange(message: Message, exchangeName: String, confirm: Boolean = false)

///**
// * add a new Returnlistener to be notified of failed deliveries when
// * PublishToExchange is used with "mandatory" or "immediate" flags set.
// * The ReturnListener actor will receive ReturnedMessage objects
// */
//case class AddReturnListener(returnListener: ActorRef)

sealed trait Confirm
case object Ack  extends Confirm
case object Nack extends Confirm

trait ChannelPublisher extends ConfirmListener { actor: ChannelActor =>

  def addReturnListener(channel: RabbitChannel, listener: ActorRef) = {
    channel.addReturnListener(new ReturnListener {
      def handleReturn(
          replyCode: Int,
          replyText: String,
          exchange: String,
          routingKey: String,
          properties: BasicProperties,
          body: Array[Byte]
      ): Unit = {
        listener ! ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body)
      }
    })
  }

  def setupPublisher(channel: RabbitChannel, listener: Option[ActorRef]) = {
    listener.foreach { listener =>
      addReturnListener(channel, listener)
    }
  }

  def setupConfirmingPublisher(channel: RabbitChannel, listener: Option[ActorRef]) = {
    channel.confirmSelect()
    listener.foreach { addReturnListener(channel, _) }
    channel.addConfirmListener(this)
  }

  when(Unavailable) {
    case Event(_: PublishToExchange, _) =>
      stash()
      stay()
  }

  def publisherUnhandled: StateFunction = {
    case Event(p: Publisher, _) =>
      log.debug("Switching to @ Publisher Mode, Not Available")
      stay().using(stateData.toMode(p))
    case Event(cp: ConfirmingPublisher, _) =>
      log.debug("Switching to @ ConfirmingPublisher Mode, Not Available")
      stay().using(stateData.toMode(cp))
  }

  when(Available) {
    case Event(PublishToExchange(message, exchangeName, true), Some(channel) %: _ %: ConfirmingPublisher(_)) =>
      //      val confirmPromise = Promise[Confirm]
      //      sender ! confirmPromise
      val returnToSender = sender()

      import message._
      log.debug("Publishing confirmed on '{}': {}", exchangeName, message)
      val s          = serialization.findSerializerFor(payload)
      val serialized = s.toBinary(payload)
      val seqNo      = channel.getNextPublishSeqNo
      try {
        lock.synchronized {
          unconfirmedSet.add(seqNo)
          confirmHandles.put(seqNo, returnToSender)
        }
        channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), serialized)
      } catch {
        case _: Throwable =>
          unconfirmedSet.remove(seqNo)
          confirmHandles.remove(seqNo, returnToSender)
      }
      stay()
    case Event(PublishToExchange(message, exchangeName, false), Some(channel) %: _ %: _) =>
      import message._
      log.debug("Publishing on '{}': {}", exchangeName, message)
      val s          = serialization.findSerializerFor(payload)
      val serialized = s.toBinary(payload)
      channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), serialized)
      stay()

    case Event(p @ Publisher(listener), Some(channel) %: _ %: _) =>
      log.debug("Switching to @ Publisher Mode, Available")
      setupPublisher(channel, listener)
      stay().using(stateData.toMode(p))

    case Event(cp @ ConfirmingPublisher(listener), Some(channel) %: _ %: _) =>
      log.debug("Switching to @ ConfirmingPublisher Mode, Available")
      setupConfirmingPublisher(channel, listener)

      stay().using(stateData.toMode(cp))
  }

  onTransition {
    //really would prefer to just "Let it crash" when a channel disconnects,
    //instead of reloading the state for this actor...not sure how that would work with the autoreconnect functionality though
    case Unavailable -> Available if nextStateData.isPublisher =>
      val Some(_) %: _ %: Publisher(listener) = nextStateData
      context.self ! Publisher(listener) //switch to modes again before unstashing messages!
      unstashAll()
    case Unavailable -> Available if nextStateData.isConfirmingPublisher =>
      val Some(_) %: _ %: ConfirmingPublisher(listener) = nextStateData
      context.self ! ConfirmingPublisher(listener) //switch to modes again before unstashing messages!
      unstashAll()
  }

  lazy val lock: AnyRef           = new Object();
  private lazy val confirmHandles = new ConcurrentHashMap[Long, ActorRef]().asScala
  private lazy val unconfirmedSet =
    Collections.synchronizedSortedSet(new TreeSet[Long]()) //Synchronized set must be in sychronized block!

  //  /**
  //   * Seems like there should be a better way to construct this so that I dont need the preConfirmed HashMap
  //   */
  //  private val preConfirmed = new ConcurrentHashMap[Long, Confirm]().asScala

  /**
   * implements the RabbitMQ ConfirmListener interface.
   */
  private[amqp] def handleAck(seqNo: Long, multiple: Boolean) = handleConfirm(seqNo, multiple, true)

  /**
   * implements the RabbitMQ ConfirmListener interface.
   */
  private[amqp] def handleNack(seqNo: Long, multiple: Boolean) = handleConfirm(seqNo, multiple, false)

  private def handleConfirm(seqNo: Long, multiple: Boolean, ack: Boolean) =
    lock.synchronized {

      if (multiple) {
        val headSet = unconfirmedSet.headSet(seqNo + 1)
        headSet.asScala.foreach(complete)
        headSet.clear();
      } else {
        unconfirmedSet.remove(seqNo);
        complete(seqNo)
      }

      def complete(seqNo: Long): Unit = {
        confirmHandles.remove(seqNo).foreach {
          _ ! (if (ack) Ack else Nack)
        }
      }
    }

}

//trait CanBuildDurablePublisher { durableChannel: DurableConnection#DurableChannel =>
//  def persistentChannel: Boolean
//  import durableChannel._
//
//  def newPublisher(exchange: ExchangeDeclaration): Future[DurablePublisher] =
//    durableChannel.withChannel(rc => new DurablePublisher(exchange(rc)))
//
//  def newPublisher(exchange: DeclaredExchange) = new DurablePublisher(exchange)
//
//  trait ChannelPublisherOld { actor: ChannelActor =>
//    import extension._
//    val exchange: DeclaredExchange
//    //implicit val system = durableConnection.connectionProperties.system
//    protected val log = Logging(system, this.getClass)
//
//    // val latch = new CountDownLatch(1)
//
//    val exchangeName = exchange.name
//
//    def onReturn(callback: ReturnedMessage => Unit) {
//      whenAvailable { channel =>
//        channel.addReturnListener(new ReturnListener {
//          def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) {
//            callback.apply(ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body))
//          }
//        })
//      }
//    }
//
//    def publish(message: Message) {
//      channelActor ! PublishToExchange(message, exchangeName)
//    }
//    def !(message: Message) {
//      channelActor ! PublishToExchange(message, exchangeName)
//    }
//  }
//
//}

//trait CanBuildConfirmingPublisher extends CanBuildDurablePublisher {
//  durableChannel : DurableConnection#DurableChannel =>
//
//  val persistentChannel = false
//
//
//     /**
//   * persistence should be false
//   */
//	def newConfirmingPublisher(exchange:DeclaredExchange) =
//	  new DurablePublisher(exchange) with ConfirmingPublisher
//
//
//
//trait ConfirmingPublisher extends ConfirmListener {
//  this: CanBuildDurablePublisher#DurablePublisher =>
//
//
//  val lock : AnyRef = new Object();
//  private val confirmHandles = new ConcurrentHashMap[Long, Promise[Confirm]]().asScala
//  private val unconfirmedSet = Collections.synchronizedSortedSet(new TreeSet[Long]()) //Synchronized set must be in sychronized block!
//
//  /**
//   * Seems like there should be a better way to construct this so that I dont need the preConfirmed HashMap
//   */
//  private val preConfirmed = new ConcurrentHashMap[Long, Confirm]().asScala
//
//  durableChannel.whenAvailable { channel =>
//    channel.confirmSelect()
//    channel.addConfirmListener(this)
//  }
//
//  def publishConfirmed(message: Message, timeout: Duration = settings.publisherConfirmTimeout): Future[Confirm] = {
//    log.debug("Publishing on '{}': {}", exchangeName, message)
//    implicit val to = Timeout(timeout)
//	import ExecutionContext.Implicits.global
//    val confirmPromise = Promise[Confirm]
//    val seqNoFuture = (channelActor ? PublishToExchange(message, exchangeName, true)).mapTo[Long]
//    seqNoFuture.onSuccess {
//      case seqNo =>
//      lock.synchronized {
//       preConfirmed.remove(seqNo) match {
//        case Some(confirm) => confirmPromise.success(confirm)
//        case None =>
//          unconfirmedSet.add(seqNo)
//        confirmHandles.put(seqNo, confirmPromise)
//      }
//      }
//    }
//    confirmPromise.future
//  }
//
//
//
//  /**
//   * implements the RabbitMQ ConfirmListener interface.
//   */
//  private[amqp] def handleAck(seqNo: Long, multiple: Boolean) = handleConfirm(seqNo, multiple, true)
//
//   /**
//   * implements the RabbitMQ ConfirmListener interface.
//   */
//  private[amqp] def handleNack(seqNo: Long, multiple: Boolean) = handleConfirm(seqNo, multiple, false)
//
//  private def handleConfirm(seqNo: Long, multiple: Boolean, ack: Boolean) = lock.synchronized {
//
//    if (multiple) {
//      val headSet = unconfirmedSet.headSet(seqNo + 1)
//      headSet.asScala.foreach(complete)
//      headSet.clear();
//    } else {
//      unconfirmedSet.remove(seqNo);
//      complete(seqNo)
//    }
//
//    def complete(seqNo: Long) {
//      confirmHandles.remove(seqNo) match {
//        case Some(x) => x.success(if (ack) Ack else Nack)
//        case None => //the confirm happened before we got the SeqNo back, sadly this happens faairly often.
//          preConfirmed.put(seqNo, if (ack) Ack else Nack)
//      }
//
//    }
//  }
//
//}
//}
