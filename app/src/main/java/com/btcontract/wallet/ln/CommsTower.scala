package com.btcontract.wallet.ln

import fr.acinq.eclair._
import scala.concurrent._
import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import rx.lang.scala.{Subscription, Observable => Obs}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import com.btcontract.wallet.ln.crypto.Tools.{Bytes, \, log, none}
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

  def listen(listeners1: Set[ConnectionListener], pkap: PublicKeyAndPair, ann: NodeAnnouncement): Unit = synchronized {
    // Update and either insert a new worker or fire onOperational on new listeners iff worker currently exists and is online
    listeners(pkap) ++= listeners1

    workers.get(pkap) match {
      case Some(worker) => for (init <- worker.theirInit) worker.handleTheirInit(listeners1, init) // Maybe inform
      case None => workers(pkap) = new Worker(pkap, ann, new Bytes(1024), new Socket) // Create and connect right away
    }
  }

  class Worker(val pkap: PublicKeyAndPair, val ann: NodeAnnouncement, buffer: Bytes, sock: Socket) { me =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor

    var pingState: Int = AWAITING_MESSAGES
    var theirInit: Option[Init] = None
    var pinging: Subscription = _

    def disconnect: Unit = try sock.close catch none
    def lsts: Set[ConnectionListener] = listeners(pkap)

    def handleTheirInit(listeners1: Set[ConnectionListener], theirInitMsg: Init): Unit = {
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
      handler process Ping(data = randomBytes(length), pongLength = length)
      pingState = AWAITING_PONG
    }

    val handler: TransportHandler =
      new TransportHandler(pkap.keyPair, ann.nodeId.value) {
        def handleEncryptedOutgoingData(data: ByteVector): Unit =
          try sock.getOutputStream.write(data.toArray) catch handleError

        def handleDecryptedIncomingData(data: ByteVector): Unit = {
          // Prevent pinger from disconnecting or sending pings
          pingState = PROCESSING_DATA

          lightningMessageCodec.decode(data.bits).require.value match {
            case Ping(replyLength, _) if replyLength > 0 => handler process Pong(ByteVector fromValidHex "00" * replyLength)
            case hostedMessage: HostedChannelMessage => for (lst <- lsts) lst.onHostedMessage(me, hostedMessage)
            case theirInitMessage: Init => handleTheirInit(lsts, theirInitMessage)
            case message => for (lst <- lsts) lst.onMessage(me, message)
          }

          // Allow pinger operations again
          pingState = AWAITING_MESSAGES
        }

        def handleEnterOperationalState: Unit = {
          handler process LNParams.makeLocalInitMessage
          pinging = Obs.interval(15.seconds).map(_ => secureRandom nextInt 10) subscribe { length =>
            // We disconnect if we are still awaiting Pong since our last sent Ping, meaning peer sent nothing back
            // otherise we send a Ping and enter awaiting Pong unless we are currently processing an incoming message
            if (AWAITING_PONG == pingState) disconnect else if (AWAITING_MESSAGES == pingState) sendPingAwaitPong(length + 1)
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