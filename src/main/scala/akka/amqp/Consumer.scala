package akka.amqp

import akka.actor.ActorRef
import Queue._

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import util.control.Exception

import java.io.IOException
import akka.actor.ActorRef
import akka.event.Logging
import scala.concurrent.Future
import scala.concurrent.Promise
import akka.actor.ActorSystem
import ChannelActor._
//trait CanStop {
//  def stop : Unit
//} 
//
case class ReturnedMessage(replyCode: Int,
                           replyText: String,
                           exchange: String,
                           routingKey: String,
                           properties: BasicProperties,
                           body: Array[Byte])

case class Delivery(payload: Array[Byte],
                    routingKey: String,
                    deliveryTag: Long,
                    isRedeliver: Boolean,
                    properties: BasicProperties,
                    channelActor: ActorRef) {

  def acknowledge(deliveryTag: Long, multiple: Boolean = false) {
    if (!channelActor.isTerminated) channelActor ! OnlyIfAvailable(_.basicAck(deliveryTag, multiple))
  }

  def reject(deliveryTag: Long, reQueue: Boolean = false) {
    if (!channelActor.isTerminated) channelActor ! OnlyIfAvailable(_.basicReject(deliveryTag, reQueue))
  }
}

//object DurableConsumer {
//  import scala.concurrent.ExecutionContext.Implicits.global
//  
//  def apply(channel: RabbitChannel)(queue: DeclaredQueue,
//                      deliveryHandler: ActorRef,
//                      autoAck: Boolean,
//                      queueBindings: QueueBinding*) : DurableConsumer = {
//    implicit val c = channel
//   new DurableConsumer(queue,deliveryHandler,autoAck, queueBindings : _*)
//  }
//  
//  def apply(queue: DeclaredQueue,
//                      deliveryHandler: ActorRef,
//                      autoAck: Boolean,
//                      queueBindings: QueueBinding*) : Future[DurableConsumer] = {
//
//   durableChannel.withChannel{ implicit c =>
//      new DurableConsumer(queue,deliveryHandler,autoAck, queueBindings : _*)
//      
//      }
//  }        
//    
//}
trait ChannelConsumer { channelActor: ChannelActor ⇒

  /**
   * declare QueueBindings if given, declare Queue and Exchange if they were given to the QueueBinding as undeclared
   * setup the Consumer and store the tag.
   * track the Queue and Exchange if they were declared, as well as the tag and QueueBinding.
   *
   */
  def setupConsumer(channel: RabbitChannel, listener: ActorRef, autoAck: Boolean, bindings: Seq[QueueBinding]): ConsumerMode = {

    //use mutable values to track what queues and exchanges have already been declared
    var uniqueDeclaredQueues = List.empty[DeclaredQueue]
    var uniqueDeclaredExchanges = List.empty[DeclaredExchange]
    var defaultQueue: Option[DeclaredQueue] = None
    bindings foreach { binding ⇒

      //add to declaredQueues on declare, so that declaredQueues is unique
      val declaredQueue = binding.queue match {
        case q: UndeclaredQueue if (q.nameOption.isEmpty) ⇒
          if (defaultQueue.isEmpty) {
            //if the Default Queue is declared, return to sender.
            val declared = q.declare(channel, context.system)
            defaultQueue = Some(declared)
            sender ! declared
            declared
          } else {
            defaultQueue.get
          }
        case q: UndeclaredQueue ⇒
          val dqOption = uniqueDeclaredQueues.collectFirst { case dq if dq.name == q.nameOption.get ⇒ dq }
          if (dqOption.isEmpty) {
            val declared = q.declare(channel, context.system)
            uniqueDeclaredQueues = q.declare(channel, context.system) :: uniqueDeclaredQueues
            declared
          } else {
            dqOption.get
          }
        case q: DeclaredQueue ⇒
          if (!uniqueDeclaredQueues.contains(q)) {
            uniqueDeclaredQueues = q :: uniqueDeclaredQueues
          }
          q
      }

      val declaredExchange = binding.exchange match {
        case e: UndeclaredExchange ⇒ e.declare(channel, context.system)
        case e: DeclaredExchange   ⇒ e
      }
      if (declaredExchange.name != "") { //do not QueueBind the namelessExchange
        //declare queueBinding
        import scala.collection.JavaConverters._
        //require(binding.routingKey != null, "the routingKey must not be null to bind " + declaredExchange.name + " >> " + declaredQueue.name)
        channel.queueBind(declaredQueue.name, declaredExchange.name, binding.routingKey, binding.getArgs.map(_.toMap.asJava).getOrElse(null))
      }
    }

    val distinctlyNamedQueues = defaultQueue.toList ::: uniqueDeclaredQueues

    val tags = distinctlyNamedQueues map { queue ⇒
      val tag = channel.basicConsume(queue.name, autoAck, new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
          import envelope._
          listener ! Delivery(body, getRoutingKey, getDeliveryTag, isRedeliver, properties, context.self)
        }
      })
      tag
    }
    ConsumerMode(listener, autoAck, bindings, tags)
  }

  when(Available) {
    case Event(Consumer(listener, autoAck, bindings), Some(channel) %: _ %: _) ⇒
      log.debug("Switching to Consumer Mode, Available")
      stay() using stateData.toMode(setupConsumer(channel, listener, autoAck, bindings))
  }

  def consumerUnhandled: StateFunction = {
    case Event(Consumer(listener, autoAck, bindings), data) ⇒
      log.debug("Switching to Consumer Mode, Not Available")
      //switch modes and save the message contents so that when we transition back to Available we know how to wire things up
      stay() using stateData.toMode(ConsumerMode(listener, autoAck, bindings, Seq.empty))
  }

  onTransition {
    //really would prefer to just "Let it crash" when a channel disconnects,
    //instead of reloading the state for this actor...not sure how that would work with the autoreconnect functionality though
    case Unavailable -> Available if nextStateData.isConsumer ⇒
      val _ %: _ %: ConsumerMode(listener, autoAck, bindings, _) = nextStateData
      context.self ! Consumer(listener, autoAck, bindings) //switch to modes again before unstashing messages!
      unstashAll()
  }

  def consumerTermination: PartialFunction[StopEvent, Unit] = {
    case StopEvent(_, state, ChannelData(Some(channel), callbacks, ConsumerMode(_, _, _, tags))) ⇒
      Exception.ignoring(classOf[ShutdownSignalException], classOf[IOException]) {
        tags foreach { tag ⇒ channel.basicCancel(tag) }
      }
      terminateWhenActive(channel)
  }
}
