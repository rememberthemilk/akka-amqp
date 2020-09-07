package akka.amqp
import scala.concurrent.duration._
import java.lang.Thread
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import akka.actor._
import util.control.Exception

sealed trait ConnectionState
case object Disconnected extends ConnectionState
case object Connected    extends ConnectionState

sealed trait ConnectionMessage
case object Connect                                           extends ConnectionMessage
case object Disconnect                                        extends ConnectionMessage
case class WithConnection[T](callback: RabbitConnection => T) extends ConnectionMessage

private[amqp] class ReconnectTimeoutGenerator {

  var currentTimeout  = 1;
  var previousTimeout = 1
  def nextTimeoutSec(maxTimeoutSec: Int): Int = {
    if (currentTimeout < maxTimeoutSec) {
      val result      = currentTimeout
      val nextTimeout = currentTimeout + previousTimeout
      currentTimeout = nextTimeout
      previousTimeout = result
      result
    } else {
      maxTimeoutSec
    }
  }

  def reset(): Unit = {
    currentTimeout = 1
    previousTimeout = 1
  }
}
case class CreateChannel(stashMessages: Boolean = true)

class ConnectionActor private[amqp] (connectionFactory: ConnectionFactory, settings: AmqpSettings)
    extends Actor
    with FSM[ConnectionState, Option[RabbitConnection]]
    with ShutdownListener {

  import settings._
  connectionFactory.setRequestedHeartbeat(amqpHeartbeat.toSeconds.toInt)
  connectionFactory.setUsername(user)
  connectionFactory.setPassword(pass)
  connectionFactory.setVirtualHost(vhost)
  connectionFactory.setAutomaticRecoveryEnabled(false)
  connectionFactory.setTopologyRecoveryEnabled(false)

  lazy val timeoutGenerator = new ReconnectTimeoutGenerator

  val executorService = Executors.newFixedThreadPool(
    channelThreads,
    new ThreadFactory {
      import connectionFactory._
      val uri     = "amqp://%s@%s:%s%s".format(getUsername, getHost, getPort, getVirtualHost)
      val counter = new AtomicInteger()
      def newThread(r: Runnable) = {
        val t = new Thread(r)
        t.setDaemon(true)
        t.setName("%s:channel-%s".format(uri, counter.incrementAndGet()))
        t
      }
    }
  )

  /**
   * Creates a new channel actor. By default this actor will be able to
   *  stash messages if it gets disconnected. It will unstash them after reconnecting.
   */
  def newChannelActor(stashMessages: Boolean = true) =
    context.actorOf {
      ChannelActor(stashMessages, settings)
    }

  startWith(Disconnected, None)

  when(Disconnected) {
    case Event(CreateChannel(stashMessages), _) =>
      val channelActor = newChannelActor(stashMessages)
      sender ! channelActor //return channelActor to sender, but in a disconnected state
      stay()
    case Event(Connect, _) =>
      log.info("Connecting to one of [{}]", addresses.mkString(", "))
      try {
        val connection =
          connectionFactory.newConnection(executorService, addresses.map(RabbitAddress.parseAddress).toArray)
        connection.addShutdownListener(this)
        log.info("Successfully connected to {}", connection)
        cancelTimer("reconnect")
        timeoutGenerator.reset()

        goto(Connected).using(Some(connection))
      } catch {
        case e: Exception =>
          log.error(e, "Error while trying to connect")
          val nextReconnectTimeout = timeoutGenerator.nextTimeoutSec(maxReconnectDelay.toSeconds.toInt)
          startTimerWithFixedDelay("reconnect", Connect, nextReconnectTimeout.seconds)
          log.info("Reconnecting in {} seconds...", nextReconnectTimeout)
          stay()
      }
    case Event(Disconnect, _) =>
      cancelTimer("reconnect")
      log.info("Already disconnected")
      stay()
    case Event(_: ShutdownSignalException, _) =>
      stay()
  }

  when(Connected) {
    case Event(RequestNewChannel, Some(connection)) =>
      //a channel must have broken, send the ChannelActor back a new one.
      sender ! NewChannel(connection.createChannel)
      stay()
    case Event(CreateChannel(persistent), Some(connection)) =>
      val channelActor = newChannelActor(persistent)
      //give the channelActor it's first channel
      channelActor ! NewChannel(connection.createChannel)
      sender ! channelActor //return channelActor to sender
      stay()

    case Event(WithConnection(callback), Some(connection)) =>
      stay().replying(callback(connection))
    case Event(Disconnect, Some(connection)) =>
      try {
        log.debug("Disconnecting from {}", connection)
        connection.close()
        log.info("Successfully disconnected from {}", connection)
      } catch {
        case e: Exception => log.error(e, "Error while closing connection")
      }
      goto(Disconnected)
    case Event(cause: ShutdownSignalException, Some(connection)) =>
      if (cause.isHardError) {
        log.error(cause, "Connection broke down {}", connection)

        //if we are going to issue another connect command.
        //Then we should make absolutely sure this connection is shut down first. (sometimes it isn't, particularly during testing)
        connection.removeShutdownListener(this)
        Exception.ignoring(classOf[AlreadyClosedException]) {
          connection.close()
        }

        self ! Connect
      }
      goto(Disconnected)
  }

  onTransition {
    case Disconnected -> Connected =>
      nextStateData match {
        case Some(connection) =>
          //send new channels to the child ChannelActors so they can reconnect
          context.children.foreach { _ ! NewChannel(connection.createChannel) }
        case None => //should never happen
          throw new Exception("The Connected state should never be without a connection!")
      }
    case Connected -> Disconnected =>
      //notify children of the disconnect
      context.children.foreach { _ ! ConnectionDisconnected }

  }

  initialize

  onTermination {
    case StopEvent(_, _, _) =>
      stateData.foreach { c =>
        Exception.ignoring(classOf[AlreadyClosedException]) {
          c.close()
        }
      }
      log.debug("Successfully disposed")
      executorService.shutdown()
  }

  def shutdownCompleted(cause: ShutdownSignalException): Unit = {
    self ! cause
  }
}

private[amqp] case class NewChannel(channel: RabbitChannel)
private[amqp] object ConnectionDisconnected
//private[amqp] object ConnectionConnected {
//  def unapply(msg: Any) = msg match {
//    case Transition(connection, _, Connected) => Some(connection)
//    case CurrentState(connection, Connected)  => Some(connection)
//    case _                                    => None
//  }
//}
//
//private[amqp] object ConnectionDisconnected {
//  def unapply(msg: Any) = msg match {
//    case Transition(_, _, Disconnected) => true
//    case CurrentState(_, Disconnected)  => true
//    case _                              => false
//  }
//}

//trait ChannelBuilders {
//  implicit val extension : AmqpExtensionImpl
//  protected val connection : DurableConnection
//  lazy val system : ActorSystem = extension._system
//
//  def newChannel(persistent: Boolean = false) = new connection.DurableChannel {
//    override val persistentChannel = persistent
//  }
//
//  def newChannelForPublisher(persistent: Boolean = false) = new connection.DurableChannel with CanBuildDurablePublisher {
//   override val persistentChannel = persistent
//  }
//
//  /**
//   * persistence is always false on the ConfirmingPublisher
//   */
//  def newChannelForConfirmingPublisher = new connection.DurableChannel with CanBuildConfirmingPublisher
//
//  def newChannelForConsumer(persistent: Boolean = false) = new connection.DurableChannel with CanBuildDurableConsumer {
//   override val persistentChannel = persistent
//  }
//
//
//}
//
//class DurableConnection private[amqp] (implicit val extension: AmqpExtensionImpl) extends HasDurableChannel {
//
//override protected val connection = this
//import extension._
//
////  def withConnection[T : reflect.ClassTag](callback: RabbitConnection => T): Future[T] = {
////    import ExecutionContext.Implicits.global
////    implicit val timeout = Timeout(settings.interactionTimeout)
////    (durableConnectionActor ? WithConnection(callback)).mapTo[T]
////  }
//
////
////
////  /**
////   * have the actor disconnect
////   */
////  def disconnect() {
////    durableConnectionActor ! Disconnect
////  }
////
////  /**
////   * tell the Actor to connect
////   */
////  def connect() {
////    durableConnectionActor ! Connect
////  }
//
////  /**
////   * kill the connection and the actor handling that connection
////   */
////  def dispose() {
////    if (!durableConnectionActor.isTerminated) {
////      durableConnectionActor ! Disconnect
////      durableConnectionActor ! PoisonPill
////    }
////  }
//}
