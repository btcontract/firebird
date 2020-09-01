package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import scala.concurrent.duration._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.HostedChannel._
import com.btcontract.wallet.ln.ChannelListener.{Incoming, Transition}
import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, FailurePacket, PaymentPacket}
import fr.acinq.eclair.wire.OnionCodecs.MissingRequiredTlv
import com.btcontract.wallet.helper.ThrottledWork
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import rx.lang.scala.Observable
import scala.concurrent.Future
import scodec.bits.ByteVector
import scodec.Attempt


class ChannelMaster(payBag: PaymentInfoBag, chanBag: ChannelBag, val pf: PathFinder, cl: ChainLink) extends ChannelListener { me =>
  var all: Vector[HostedChannel] = chanBag.all.map(createHostedChannel)
  val paymentMaster = new PaymentMaster(me)

  var listeners = Set.empty[ChannelMasterListener]
  val events: ChannelMasterListener = new ChannelMasterListener {
    override def incomingAllShardsCleared(paymentHash: ByteVector32): Unit = for (lst <- listeners) lst.incomingAllShardsCleared(paymentHash)
    override def outgoingSucceeded(paymentHash: ByteVector32): Unit = for (lst <- listeners) lst.outgoingSucceeded(paymentHash)
    override def outgoingFailed(paymentHash: ByteVector32): Unit = for (lst <- listeners) lst.outgoingFailed(paymentHash)
  }

  private val preliminaryResolveMemo = memoize(preliminaryResolve)
  private val getPaymentInfoMemo = memoize(payBag.getPaymentInfo)

  val incomingTimeoutWorker: ThrottledWork[ByteVector, Any] = new ThrottledWork[ByteVector, Any] {
    // We always resolve incoming payments in same channel thread to avoid concurrent messaging issues
    def process(paymenthash: ByteVector, res: Any): Unit = Future(processIncoming)(channelContext)
    def work(paymenthash: ByteVector): Observable[Null] = RxUtils.ioQueue.delay(60.seconds)
    def error(canNotHappen: Throwable): Unit = none
  }

  val realConnectionListener: ConnectionListener = new ConnectionListener {
    // For messages we should differentiate by channelId, but we don't since only one hosted channel per node is allowed
    override def onOperational(worker: CommsTower.Worker): Unit = fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_ONLINE)
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_OFFLINE)
      RxUtils.ioQueue.delay(5.seconds).foreach(_ => initConnect)
    }
  }

  def createHostedChannel(cd: ChannelData): HostedChannel = new HostedChannel {
    def SEND(msg: LightningMessage *): Unit = for (work <- CommsTower.workers get data.announce.nodeSpecificPkap) msg foreach work.handler.process
    def STORE(channelData: HostedCommits): HostedCommits = chanBag.put(channelData.announce.nodeSpecificHostedChanId, channelData)
    def currentBlockDay: Long = cl.currentChainTip / LNParams.blocksPerDay
    listeners = Set(me, paymentMaster)
    doProcess(cd)
  }

  def incorrectDetails(add: UpdateAddHtlc) = IncorrectOrUnknownPaymentDetails(add.amountMsat, cl.currentChainTip)
  def failFinalPayloadSpec(fail: FailureMessage, finalPayloadSpec: FinalPayloadSpec): CMD_FAIL_HTLC = failHtlc(finalPayloadSpec.packet, fail, finalPayloadSpec.add)
  def failHtlc(packet: DecryptedPacket, fail: FailureMessage, add: UpdateAddHtlc): CMD_FAIL_HTLC = CMD_FAIL_HTLC(FailurePacket.create(packet.sharedSecret, fail), add)

  def fromNode(nodeId: PublicKey): Vector[HostedChannel] = for (chan <- all if chan.data.announce.na.nodeId == nodeId) yield chan
  def findById(from: Vector[HostedChannel], chanId: ByteVector32): Option[HostedChannel] = from.find(_.data.announce.nodeSpecificHostedChanId == chanId)
  def initConnect: Unit = for (channel <- all) CommsTower.listen(Set(realConnectionListener), channel.data.announce.nodeSpecificPkap, channel.data.announce.na)

  def processIncoming: Unit = {
    val allIncomingResolves: Vector[AddResolution] = all.flatMap(_.chanAndCommitsOpt).flatMap(_.commits.pendingIncoming).map(preliminaryResolveMemo)
    val badRightAway: Vector[BadAddResolution] = allIncomingResolves collect { case badAddResolution: BadAddResolution => badAddResolution }
    val maybeGood: Vector[FinalPayloadSpec] = allIncomingResolves collect { case finalPayloadSpec: FinalPayloadSpec => finalPayloadSpec }

    // Grouping by payment hash assumes we never ask for two different payments with the same hash!
    val results = maybeGood.groupBy(_.add.paymentHash).map(_.swap).mapValues(getPaymentInfoMemo) map {
      case payments \ None => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ _ if payments.map(_.payload.totalAmount).toSet.size > 1 => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ Some(info) if payments.exists(_.payload.totalAmount < info.amountOrZero) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ Some(info) if !payments.flatMap(_.payload.paymentSecret).forall(info.pr.paymentSecret.contains) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)

      case payments \ Some(info) if payments.map(_.add.amountMsat).sum >= payments.head.payload.totalAmount =>
        // We have collected enough incoming HTLCs to cover our requested amount, fulfill them all at once
        val result = for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
        // Also clear memos to get rid of old copies (see next case)
        preliminaryResolveMemo.clear
        getPaymentInfoMemo.clear
        result

      // This is an unexpected extra-payment or something like OFFLINE channel with fulfilled payment becoming OPEN, silently fullfil these
      case payments \ Some(info) if info.isIncoming && info.status == PaymentInfo.SUCCESS => for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
      case payments \ _ if incomingTimeoutWorker.hasFinishedOrNeverStarted => for (pay <- payments) yield failFinalPayloadSpec(PaymentTimeout, pay)
      case _ => Vector.empty
    }

    // Use `doProcess` to resolve all payments within this single call inside of channel executor
    // this whole method MUST itself be called within a channel executor to avoid concurrency issues
    for (cmdResolution <- results.flatten) findById(all, cmdResolution.add.channelId).foreach(_ doProcess cmdResolution)
    for (cmdResolution <- badRightAway) findById(all, cmdResolution.add.channelId).foreach(_ doProcess cmdResolution)
    for (chan <- all) chan doProcess CMD_PROCEED
  }

  // Executed in channelContext
  override def stateUpdated(hc: HostedCommits): Unit = {
    val allChansAndCommits: Vector[ChanAndCommits] = all.flatMap(_.chanAndCommitsOpt)
    val allFulfilledHashes = allChansAndCommits.flatMap(_.commits.localSpec.localFulfilled) // Shards fulfilled on last state update
    val allIncomingHashes = allChansAndCommits.flatMap(_.commits.localSpec.incomingAdds).map(_.paymentHash) // Shards still unfinalized
    allFulfilledHashes.diff(allIncomingHashes).foreach(events.incomingAllShardsCleared) // No pending payments left
    processIncoming
  }

  override def onProcessSuccess: PartialFunction[Incoming, Unit] = {
    // An incoming payment arrives so we prolong time-outed waiting for the rest of shards
    case (_, _, add: UpdateAddHtlc) => incomingTimeoutWorker replaceWork add.paymentHash
  }

  override def onBecome: PartialFunction[Transition, Unit] = {
    // Offline channel does not react to our commands so we resend them on reconnect
    case (_, _, WAIT_FOR_ACCEPT | SLEEPING, OPEN | SUSPENDED) => processIncoming
  }

  private def preliminaryResolve(add: UpdateAddHtlc): AddResolution =
    PaymentPacket.peel(LNParams.keys.makeFakeKey(add.paymentHash), add.paymentHash, add.onionRoutingPacket) match {
      case Right(packet) if packet.isLastPacket => OnionCodecs.finalPerHopPayloadCodec.decode(packet.payload.bits) match {
        case Attempt.Failure(error: MissingRequiredTlv) => failHtlc(packet, InvalidOnionPayload(error.tag, offset = 0), add)
        case _: Attempt.Failure => failHtlc(packet, InvalidOnionPayload(tag = UInt64(0), offset = 0), add)

        case Attempt.Successful(payload) if payload.value.expiry != add.cltvExpiry => failHtlc(packet, FinalIncorrectCltvExpiry(add.cltvExpiry), add)
        case Attempt.Successful(payload) if payload.value.amount != add.amountMsat => failHtlc(packet, incorrectDetails(add), add)
        case Attempt.Successful(payload) => FinalPayloadSpec(packet, payload.value, add)
      }

      case Right(packet) => failHtlc(packet, incorrectDetails(add), add)
      case Left(error) => CMD_FAIL_MALFORMED_HTLC(error.onionHash, error.code, add)
    }

  def maxReceivableInfo: Option[CommitsAndMax] = {
    val canReceive = all.flatMap(_.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).sortBy(_.commits.nextLocalSpec.toRemote)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivalble = 50*3 = 150 (the idea is for smallest remaining channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.nextLocalSpec.toRemote * canReceive.size < canReceive.last.commits.nextLocalSpec.toRemote).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.nextLocalSpec.toRemote * cs.size)
    maxByOption[CommitsAndMax, MilliSatoshi](candidates, _.maxReceivable)
  }

  def checkIfSendable(paymentHash: ByteVector32, amount: MilliSatoshi): Int = {
    val fulfilledLongTimeAgo = payBag.getPaymentInfo(paymentHash).map(_.status) contains PaymentInfo.SUCCESS
    val inChannels = all.flatMap(_.chanAndCommitsOpt).flatMap(_.commits.pendingOutgoing).exists(_.paymentHash == paymentHash)
    val absentOrFinalized = paymentMaster.data.payments.get(paymentHash).forall(_.isFinalized)

    if (paymentMaster.canSendInPrinciple < amount) PaymentInfo.NOT_SENDABLE_LOW_BALANCE
    else if (inChannels || !absentOrFinalized) PaymentInfo.NOT_SENDABLE_IN_FLIGHT
    else if (fulfilledLongTimeAgo) PaymentInfo.NOT_SENDABLE_SUCCESS
    else PaymentInfo.SENDABLE
  }
}

trait ChannelMasterListener {
  def incomingAllShardsCleared(paymentHash: ByteVector32): Unit = none
  def outgoingSucceeded(paymentHash: ByteVector32): Unit = none
  def outgoingFailed(paymentHash: ByteVector32): Unit = none
}