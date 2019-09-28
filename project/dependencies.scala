import sbt._

object dependencies {
  def AmqpClient = "com.rabbitmq" % "amqp-client" % "5.7.3"
  def AkkaAgent = "com.typesafe.akka" %% "akka-agent" % "2.5.25"
}
