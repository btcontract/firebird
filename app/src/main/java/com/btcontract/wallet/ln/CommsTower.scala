package com.btcontract.wallet.ln

import scala.concurrent._
import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import rx.lang.scala.{Subscription, Observable => Obs}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import com.btcontract.wallet.ln.crypto.Tools.{Bytes, \, log, none, random}
import fr.acinq.eclair.wire.LightningMessageCodecs.lightningMessageCodec
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Features
import scala.collection.mutable
import scodec.bits.ByteVector
import java.net.Socket


case class PublicKeyAndPair(pk: PublicKey, keyPair: KeyPair)

object CommsTower {
  type Listeners = Set[ConnectionListener]
  val workers: mutable.Map[PublicKeyAndPair, Worker] = new ConcurrentHashMap[PublicKeyAndPair, Worker].asScala
  val listeners: mutable.Map[PublicKeyAndPair, Listeners] = new ConcurrentHashMap[PublicKeyAndPair, Listeners].asScala withDefaultValue Set.empty

  def listen(listeners1: Set[ConnectionListener], pkap: PublicKeyAndPair, ann: NodeAnnouncement): Unit = synchronized {
    // Update and either insert a new worker or fire onOperational on new listeners iff worker currently exists and is online
    listeners(pkap) ++= listeners1

    workers.get(pkap) match {
      case Some(worker) => for (init <- worker.theirInit) worker.handleTheirInit(listeners1, init) // Maybe inform
      case None => workers(pkap) = new Worker(pkap, ann) // Create a new worker and connect right away
    }
  }

  class Worker(val pkap: PublicKeyAndPair, val ann: NodeAnnouncement, buffer: Bytes = new Bytes(1024), sock: Socket = new Socket) { me =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor

    var ourLastPing = Option.empty[Ping]
    var theirInit = Option.empty[Init]
    var pinging: Subscription = _

    def disconnect: Unit = try sock.close catch none
    def lsts: Set[ConnectionListener] = listeners(pkap)

    def handleTheirInit(listeners1: Set[ConnectionListener], theirInitMsg: Init): Unit = {
      // Use a separate variable for listeners because a set of listeners provided to this method may be different
      if (Features areSupported theirInitMsg.features) for (lst <- listeners1) lst.onOperational(me) else disconnect
      theirInit = Some(theirInitMsg)
    }

    val handler: TransportHandler = new TransportHandler(pkap.keyPair, ann.nodeId.value) {
      def handleEncryptedOutgoingData(data: ByteVector): Unit = try sock.getOutputStream.write(data.toArray) catch handleError
      def handleDecryptedIncomingData(data: ByteVector): Unit = (lightningMessageCodec.decode(data.bits).require.value, ourLastPing) match {
        case Ping(replyLength, _) \ _ if replyLength > 0 && replyLength <= 65532 => handler process Pong(ByteVector fromValidHex "00" * replyLength)
        case Pong(randomDataFromPeer) \ Some(ourSavedWaitingPing) if randomDataFromPeer.size == ourSavedWaitingPing.pongLength => ourLastPing = None
        case (message: HostedChannelMessage, _) => for (lst <- lsts) lst.onHostedMessage(me, message)
        case (theirInitMessage: Init, _) => handleTheirInit(lsts, theirInitMessage)
        case (message, _) => for (lst <- lsts) lst.onMessage(me, message)
      }

      def handleEnterOperationalState: Unit = {
        handler process LNParams.makeLocalInitMessage
        pinging = Obs.interval(15.seconds).map(_ => random.nextInt(10) + 1) subscribe { length =>
          val ourNextPing = Ping(data = ByteVector.view(random getBytes length), pongLength = length)
          if (ourLastPing.isEmpty) handler process ourNextPing else disconnect
          ourLastPing = Some(ourNextPing)
        }
      }

      def handleError: PartialFunction[Throwable, Unit] = { case error =>
        // Whatever happens here it's safe to disconnect right away
        log(error.getLocalizedMessage)
        disconnect
      }
    }

    val thread = Future {
      // Always use the first address, it's safe it throw here
      sock.connect(ann.addresses.head.socketAddress, 7500)
      handler.init

      while (true) {
        val length = sock.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process ByteVector.view(buffer take length)
      }
    }

    thread onComplete { _ =>
      try pinging.unsubscribe catch none
      for (lst <- lsts) lst.onDisconnect(me)
      workers -= pkap
    }
  }
}

class ConnectionListener {
  def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = none
  def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = none
  def onOperational(worker: CommsTower.Worker): Unit = none
  def onDisconnect(worker: CommsTower.Worker): Unit = none
}