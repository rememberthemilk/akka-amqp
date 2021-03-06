package akka.amqp

import akka.testkit.{AkkaSpec, TestFSMRef, TestKit, TestLatch}
import scala.concurrent.duration._
import scala.concurrent.Await
import ChannelActor._
class ChannelSpec extends AkkaSpec(AmqpConfig.Valid.config) with AmqpMock {

  "Durable Channel Actor" should {
    val channelActor = TestFSMRef(new ChannelActor(AmqpConfig.Valid.settings) {
      def stash(): Unit = {}
      def unstashAll(): Unit = {}
    })

    "start in state Unavailable" in {
      channelActor.stateName shouldBe Unavailable
    }

    "execute registered callbacks and become Available when receiving a Channel" in {
      channelActor.stateName shouldBe Unavailable
      val latch = TestLatch(15)
      (1 to 5).foreach(_ => channelActor ! ExecuteOnNewChannel(_ => latch.open()))
      (1 to 5).foreach(_ => channelActor ! WithChannel(_ => latch.open()))
      channelActor ! NewChannel(channel) // 5x WithChannel and 5x ExecuteOnNewChannel
      channelActor ! NewChannel(channel) //5x ExecuteOnNewChannel
      Await.ready(latch, 5.seconds).isOpen shouldBe true
      awaitCond(channelActor.stateName == Available, 5.seconds, 300.millis)
    }
    "register callback (ExecuteOnNewChannel) and do not execute until receiving a newChannel" in {
      channelActor.stateName shouldBe Available
      val latch = TestLatch()
      channelActor ! ExecuteOnNewChannel(_ => latch.open())
      latch.isOpen shouldBe false
      channelActor ! NewChannel(channel) // ExecuteOnNewChannel
      latch.isOpen shouldBe false
      channelActor ! ConnectionDisconnected
      latch.isOpen shouldBe false
      channelActor ! NewChannel(channel) // ExecuteOnNewChannel
      Await.ready(latch, 5.seconds).isOpen shouldBe true
    }

    "register future (WithChannel) and execute immediately when Available" in {
      channelActor.stateName shouldBe Available
      val latch = TestLatch()
      channelActor ! WithChannel(_ => latch.open())
      Await.ready(latch, 5.seconds).isOpen shouldBe true
    }

    "request new channel when channel breaks and go to Unavailble" in {
      val method = new com.rabbitmq.client.impl.AMQImpl.Access.RequestOk(1)
      channelActor ! new ShutdownSignalException(false, false, method, channel)
      channelActor.stateName shouldBe Unavailable
    }
    "go to Unavailable when connection disconnects" in new TestKit(system) with AmqpMock {
      channelActor.setState(Available, ChannelData(Some(channel), Vector.empty, BasicChannel))
      channelActor ! ConnectionDisconnected
      channelActor.stateName shouldBe Unavailable
    }

  }
}
