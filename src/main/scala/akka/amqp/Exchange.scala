package akka.amqp

import scala.jdk.CollectionConverters._
import akka.actor.ActorSystem

object Exchange {
  def nameless            = NamelessExchange
  def apply(name: String) = new ExchangeDeclarationMode(name)
  def named(name: String) = new ExchangeDeclarationMode(name)
}

sealed trait Exchange {
  val name: String
  val params: Option[ExchangeParams]

  /**
   * start the binding process to bind this Queue to an Exchange
   */
  def >>(queue: Queue) = QueueBinding0(this, queue)
  def >>(destination: Exchange)(routingKey: String)(arguments: Option[Map[String, AnyRef]] = None) = {
    ExchangeToExchangeBinding(this, destination, routingKey, arguments)
  }
}

case class ExchangeParams private[amqp] (
    exchangeType: String,
    durable: Boolean = false,
    autoDelete: Boolean = false,
    internal: Boolean = false,
    arguments: Option[Map[String, AnyRef]] = None
)

class ExchangeDeclarationMode private[amqp] (val name: String) {
  def active(
      exchangeType: String,
      durable: Boolean = false,
      autoDelete: Boolean = false,
      internal: Boolean = false,
      arguments: Option[Map[String, AnyRef]] = None
  )           = ActiveUndeclaredExchange(name, exchangeType, durable, autoDelete, internal, arguments)
  def passive = PassiveUndeclaredExchange(name)

  /**
   * create an exchange that will not declare itself with the AMQP server if you call the declare method.
   */
  def dontDeclare = DontDeclareUndeclaredExchange(name)
}

//make the declarations not use rabbitchannel, and allow access to name, etc.

sealed trait UndeclaredExchange extends Exchange with Declarable[DeclaredExchange] {
  def declare(implicit channel: RabbitChannel, system: ActorSystem): DeclaredExchange
}

case class DontDeclareUndeclaredExchange private[amqp] (val name: String) extends UndeclaredExchange {
  val params: Option[ExchangeParams] = None
  def declare(implicit channel: RabbitChannel, system: ActorSystem): NamedExchange = {
    val ok = new Object with RabbitExchange.DeclareOk {
      def protocolClassId()    = 40
      def protocolMethodId()   = 11
      def protocolMethodName() = "exchange.declare-ok"
    }

    NamedExchange(name, ok, None, this)
  }
}

case class PassiveUndeclaredExchange private[amqp] (val name: String) extends UndeclaredExchange {
  val params: Option[ExchangeParams] = None
  def declare(implicit channel: RabbitChannel, system: ActorSystem): NamedExchange = {
    val de = NamedExchange(name, channel.exchangeDeclarePassive(name), None, this)
    system.eventStream.publish(NewlyDeclared(de))
    de
  }
}
case class ActiveUndeclaredExchange private[amqp] (
    val name: String,
    exchangeType: String,
    durable: Boolean = false,
    autoDelete: Boolean = false,
    internal: Boolean = false,
    arguments: Option[Map[String, AnyRef]] = None
) extends UndeclaredExchange {

  lazy val params: Option[ExchangeParams] = Some(ExchangeParams(exchangeType, durable, autoDelete, internal, arguments))

  def declare(implicit channel: RabbitChannel, system: ActorSystem) = {
    val declareOk = channel.exchangeDeclare(
      name,
      exchangeType,
      durable,
      autoDelete,
      internal,
      arguments.map(_.asJava).getOrElse(null)
    )
    val params = ExchangeParams(exchangeType, durable, autoDelete, internal, arguments)
    val de     = NamedExchange(name, declareOk, Some(params), this)
    system.eventStream.publish(NewlyDeclared(de))
    de
  }
}

sealed trait DeclaredExchange extends Exchange

case object NamelessExchange extends DeclaredExchange {
  val name   = ""
  val params = None
}

case class NamedExchange private[amqp] (
    val name: String,
    val peer: RabbitExchange.DeclareOk,
    val params: Option[ExchangeParams] = None,
    val undeclared: UndeclaredExchange
) extends DeclaredExchange {}

case class ExchangeToExchangeBinding private[amqp] (
    source: Exchange,
    destination: Exchange,
    routingKey: String,
    arguments: Option[Map[String, AnyRef]] = None
) {

  def bind(channel: RabbitChannel): RabbitExchange.BindOk = {
    channel.exchangeBind(destination.name, source.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }

  def unbind(channel: RabbitChannel): RabbitExchange.UnbindOk = {
    channel.exchangeUnbind(destination.name, source.name, routingKey, arguments.map(_.asJava).getOrElse(null))
  }
}
