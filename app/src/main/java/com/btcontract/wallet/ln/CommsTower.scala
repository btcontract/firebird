package com.btcontract.wallet.ln

import fr.acinq.eclair._
import scala.concurrent._
import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import rx.lang.scala.{Subscription, Observable}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import com.btcontract.wallet.ln.crypto.Tools.{Bytes, \, none}
import fr.acinq.eclair.wire.LightningMessageCodecs.lightningMessageCodec
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Features
import scala.collection.mutable
import scodec.bits.ByteVector
import java.net.Socket


case class PublicKeyAndPair(them: PublicKey, keyPair: KeyPair)

object CommsTower {
  type Listeners = Set[ConnectionListener]
  val workers: mutable.Map[PublicKeyAndPair, Worker] = new ConcurrentHashMap[PublicKeyAndPair, Worker].asScala
  val listeners: mutable.Map[PublicKeyAndPair, Listeners] = new ConcurrentHashMap[PublicKeyAndPair, Listeners].asScala withDefaultValue Set.empty

  final val PROCESSING_DATA = 1
  final val AWAITING_MESSAGES = 2
  final val AWAITING_PONG = 3

  def listen(listeners1: Set[ConnectionListener], pkap: PublicKeyAndPair, ann: NodeAnnouncement, ourInit: Init): Unit = synchronized {
    // Update and either insert a new worker or fire onOperational on new listeners iff worker currently exists and is online
    // First add listeners, then try to add worker because we may already have a connected worker, but no listeners
    listeners(pkap) ++= listeners1

    workers.get(pkap) match {
      case Some(presentWorker) =>
        // A connected worker is already present, inform listener if it's established
        presentWorker.theirInit.foreach(presentWorker handleTheirInit listeners1)
      case None =>
        // No worker is present, add a new one and try to connect right away
        workers(pkap) = new Worker(pkap, ann, ourInit, new Bytes(1024), new Socket)
    }
  }

  def forget(pkap: PublicKeyAndPair): Unit = {
    // First remove all listeners, then disconnect
    // this ensures listeners won't try to reconnect

    listeners.remove(pkap)
    workers.get(pkap).foreach(_.disconnect)
  }

  class Worker(val pkap: PublicKeyAndPair, val ann: NodeAnnouncement, ourInit: Init, buffer: Bytes, sock: Socket) { me =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor

    var pingState: Int = AWAITING_MESSAGES
    var theirInit: Option[Init] = None
    var pinging: Subscription = _

    def disconnect: Unit = try sock.close catch none
    def handleTheirInit(listeners1: Set[ConnectionListener] = Set.empty)(theirInitMsg: Init): Unit = {
      // Use a separate variable for listeners here because a set of listeners provided to this method may be different
      // Account for a case where they disconnect while we are deciding on their features (do nothing in this case)
      val areSupported = Features.areSupported(theirInitMsg.features)
      theirInit = Some(theirInitMsg)

      (areSupported, thread.isCompleted) match {
        case true \ false => for (lst <- listeners1) lst.onOperational(me) // They have not disconnected yet
        case false \ false => disconnect // Their features are not supported and they have not disconnected yet
        case _ \ true => // They have disconnected at this point, all callacks are already called, do nothing
      }
    }

    def sendPingAwaitPong(length: Int): Unit = {
      val pingPayload: ByteVector = randomBytes(length)
      handler process Ping(length, pingPayload)
      pingState = AWAITING_PONG
    }

    val handler: TransportHandler =
      new TransportHandler(pkap.keyPair, ann.nodeId.value) {
        def handleEncryptedOutgoingData(data: ByteVector): Unit =
          try sock.getOutputStream.write(data.toArray) catch {
            case _: Throwable => disconnect
          }

        def handleDecryptedIncomingData(data: ByteVector): Unit = {
          // Prevent pinger from disconnecting or sending pings
          pingState = PROCESSING_DATA

          lightningMessageCodec.decode(data.bits).require.value match {
            case message: Init => handleTheirInit(listeners apply pkap)(message)
            case message: Ping => if (message.pongLength > 0) handler process Pong(ByteVector fromValidHex "00" * message.pongLength)
            case message: HostedChannelBranding => for (lst <- listeners apply pkap) lst.onBrandingMessage(me, message)
            case message: HostedChannelMessage => for (lst <- listeners apply pkap) lst.onHostedMessage(me, message)
            case message => for (lst <- listeners apply pkap) lst.onMessage(me, message)
          }

          // Allow pinger operations again
          pingState = AWAITING_MESSAGES
        }

        def handleEnterOperationalState: Unit = {
          pinging = Observable.interval(15.seconds).map(_ => secureRandom nextInt 10) subscribe { len =>
            // We disconnect if we are still awaiting Pong since our last sent Ping, meaning peer sent nothing back
            // otherise we send a Ping and enter awaiting Pong unless we are currently processing an incoming message
            if (AWAITING_PONG == pingState) disconnect else if (AWAITING_MESSAGES == pingState) sendPingAwaitPong(len + 1)
          }

          // Send our node parameters
          handler process ourInit
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
      listeners(pkap).foreach(_ onDisconnect me)
      workers -= pkap
    }
  }
}

class ConnectionListener {
  def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = none
  def onBrandingMessage(worker: CommsTower.Worker, msg: HostedChannelBranding): Unit = none
  def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = none
  def onOperational(worker: CommsTower.Worker): Unit = none
  def onDisconnect(worker: CommsTower.Worker): Unit = none
}