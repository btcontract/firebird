package com.btcontract.wallet

import fr.acinq.eclair._
import com.softwaremill.quicklens._
import com.btcontract.wallet.SyncSpec._
import com.btcontract.wallet.GraphSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.ln.wire.UpdateAddTlv
import com.btcontract.wallet.lnutils.{SQliteChannelBag, SQliteNetworkDataStore}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.channel.CMD_SOCKET_OFFLINE
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress, NodeAnnouncement, Tlv, TlvStream, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector

@RunWith(classOf[AndroidJUnit4])
class PaymentMasterSpec {
  LNParams.routerConf = LNParams.routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L), firstPassMaxCltv = CltvExpiryDelta(1008 + 504))
  LNParams.format = MnemonicStorageFormat(LightningNodeKeys.makeFromSeed(randomBytes(32).toArray))

  def makeHostedCommits(nodeId: PublicKey, alias: String, toLocal: MilliSatoshi = 100000000L.msat): HostedCommits = {
    val announce = Tools.mkNodeAnnouncement(nodeId, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), alias)
    val spec = CommitmentSpec(feeratePerKw = 0L, toLocal = toLocal, toRemote = 100000000L.msat)
    val init_hosted_channel = InitHostedChannel(UInt64(toLocal.underlying + 100000000L), 10.msat, 20, 200000000L.msat, 5000, 1000000.sat, 0.msat, ByteVector.empty)
    val lcss: LastCrossSignedState = LastCrossSignedState(refundScriptPubKey = randomBytes(119), init_hosted_channel, blockDay = 100, localBalanceMsat = toLocal, remoteBalanceMsat = 100000000L.msat,
      localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)
    HostedCommits(NodeAnnouncementExt(announce), lastCrossSignedState = lcss, futureUpdates = Vector.empty, localSpec = spec, updateOpt = None, brandingOpt = None, localError = None,
      remoteError = None, startedAt = System.currentTimeMillis)
  }

  @Test
  def splitAfterNoRouteFound(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val hcs = makeHostedCommits(nodeId = a, alias = "peer1")
    val channelBag = new SQliteChannelBag(normal.db)
    channelBag.put(ByteVector32(hcs.announce.na.nodeId.value.take(32)), hcs)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    val initialSendable = master.PaymentMaster.getSendable(master.all).values.head
    assertTrue(initialSendable == master.PaymentMaster.feeFreeBalance(master.all.head.chanAndCommitsOpt.get))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(500L))

    // Our only channel is offline
    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.head.asInstanceOf[WaitForBetterConditions].amount == cmd.totalAmount)
    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    // Graph is not yet ready
    val await = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight]
    assertTrue(await.amount == cmd.totalAmount)
    assertTrue(pf.data.extraEdges.size == 1)

    // Payment is not yet in channel, but it is waiting in sender so amount without fees is taken into account
    assertTrue(master.PaymentMaster.getSendable(master.all).values.head == initialSendable - await.amount)
    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    // First route is now overloaded, so another one is chosen
    val List(w1, w2) = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.toList.map(_.asInstanceOf[WaitForRouteOrInFlight])
    assertTrue(w1.flight.get.route.hops.map(_.desc.a) == Seq(LNParams.format.keys.routingPubKey, a, c, d))
    assertTrue(w2.flight.get.route.hops.map(_.desc.a) == Seq(LNParams.format.keys.routingPubKey, a, b, d))

    val finalSendable = master.PaymentMaster.getSendable(master.all).values.head
    // Sendable was decreased by total payment amount with fees, but slightly increased relatively because 1% of the rest of sendable is smaller than initial 1% of balance
    println(finalSendable.truncateToSatoshi == (initialSendable - w1.amountWithFees - w2.amountWithFees + (w1.amountWithFees + w2.amountWithFees) * getParams.maxFeePct).truncateToSatoshi)
  }

  @Test
  def failAfterTooManyLocalErrors(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(UInt64(10)) // Payments will fail locally
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2").modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(UInt64(10)) // Payments will fail locally
    val channelBag = new SQliteChannelBag(normal.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(500L))

    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).data.inFlights.isEmpty)
    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).state == PaymentMaster.ABORTED)
  }

  @Test
  def chanBecomesOfflineAnotherIsUsed(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    val channelBag = new SQliteChannelBag(normal.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(500L))

    // Channel is chosen, but graph is not ready
    val wait1 = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight]
    val chosenChan = master.all.find(_.data.announce.na.alias == wait1.chan.data.announce.na.alias).get

    // Graph becomes ready, but chosen chan goes offline
    chosenChan process CMD_SOCKET_OFFLINE
    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    // Payment gets split in two because no route can handle a whole and both parts end up with second channel
    val waits = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    assertTrue(waits.forall(_.chan.data.announce.na.alias != chosenChan.data.announce.na.alias))
    assertTrue(waits.size == 2)
  }

  @Test
  def reRoutedBecauseFailedAtAmount(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    val channelBag = new SQliteChannelBag(hosted.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    master.PaymentMaster.data = master.PaymentMaster.data.copy(chanFailedAtAmount = Map(desc -> 200000L.msat))

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(500L))

    val ws = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    // A single 600 payment has been split into three payments such that failed at 200 channel can handle one of the parts
    assertTrue(ws.map(_.amount).toList.sorted == List(150000.msat, 150000.msat, 300000.msat))
  }

  @Test
  def fulfillNonExistingPayment(): Unit = {
    val (normal, hosted) = getRandomStore
    var response: List[PaymentSenderData] = Nil
    fillBasicGraph(normal)

    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val channelBag = new SQliteChannelBag(hosted.db)
    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    master.listeners += new ChannelMasterListener {
      override def outgoingSucceeded(data: PaymentSenderData): Unit = response = data :: response
    }

    val fulfill = UpdateFulfillHtlc(ByteVector32.Zeroes, id = 1, paymentPreimage = ByteVector32.One)
    master.PaymentMaster process fulfill
    master.PaymentMaster process fulfill
    master.PaymentMaster process fulfill
    synchronized(wait(500L))

    assertTrue(master.PaymentMaster.data.payments(fulfill.paymentHash).state == PaymentMaster.SUCCEEDED)
    assertTrue(response.head.cmd.paymentHash == fulfill.paymentHash)
    assertTrue(response.size == 1)
  }

  @Test
  def failNonExistingPayment(): Unit = {
    val (normal, hosted) = getRandomStore
    var response: List[PaymentSenderData] = Nil
    fillBasicGraph(normal)

    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val channelBag = new SQliteChannelBag(normal.db)
    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    master.listeners += new ChannelMasterListener {
      override def outgoingFailed(data: PaymentSenderData): Unit = response = data :: response
    }

    val internalId: TlvStream[Tlv] = TlvStream(UpdateAddTlv.InternalId(ByteVector.empty) :: Nil)
    val update = UpdateAddHtlc(ByteVector32.Zeroes, 1L, 0L.msat, ByteVector32.One, CltvExpiry(0), null, internalId)
    val fail = FailAndAdd(UpdateFailHtlc(ByteVector32.Zeroes, id = 1, reason = ByteVector.empty), update)
    master.PaymentMaster process fail
    master.PaymentMaster process fail
    master.PaymentMaster process fail
    synchronized(wait(500L))

    assertTrue(master.PaymentMaster.data.payments(fail.ourAdd.paymentHash).state == PaymentMaster.ABORTED)
    assertTrue(response.head.cmd.paymentHash == fail.ourAdd.paymentHash)
    assertTrue(response.size == 3)
  }

  @Test
  def secondPaymentGetsSplit(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    val channelBag = new SQliteChannelBag(normal.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    // Throughput is 1 000 000 msat

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd1 = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 300000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd1
    synchronized(wait(500L))

    val cmd2 = CMD_SEND_MPP(paymentHash = ByteVector32.One, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.One, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd2
    synchronized(wait(500L))

    val two = ByteVector32(ByteVector.fromValidHex("0200000000000000000000000000000000000000000000000000000000000000"))
    val cmd3 = CMD_SEND_MPP(paymentHash = two, totalAmount = 200000.msat, targetNodeId = s, paymentSecret = two, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd3
    synchronized(wait(500L))

    val ws1 = master.PaymentMaster.data.payments(cmd1.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    assertTrue(ws1.map(_.amount).toList.sorted == List(300000.msat))

    val ws2 = master.PaymentMaster.data.payments(cmd2.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    assertTrue(ws2.map(_.amount).toList.sorted == List(150000.msat, 150000.msat, 300000.msat))

    assertTrue(master.PaymentMaster.data.payments(cmd3.paymentHash).state == PaymentMaster.ABORTED)
  }

  @Test
  def bumpAmountOnSplit(): Unit = {
    val (normal, hosted) = getRandomStore
    fillBasicGraph(normal)
    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    // 99 000 msat sendable in both
    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1", toLocal = 600000L.msat).modify(_.lastCrossSignedState.initHostedChannel.htlcMinimumMsat).setTo(10000.msat)
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2", toLocal = 600000L.msat).modify(_.lastCrossSignedState.initHostedChannel.htlcMinimumMsat).setTo(10000.msat)
    val channelBag = new SQliteChannelBag(normal.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl) { val socketToChannelBridge: ConnectionListener = null }

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    val (Vector(c1), Vector(c2)) = master.all.partition(_.data.announce.na.alias == "peer1")
    c1.BECOME(c1.data, HostedChannel.SLEEPING)
    c2.BECOME(c2.data, HostedChannel.OPEN)
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd1 = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 100000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd1
    synchronized(wait(500L))

    c1.BECOME(c1.data, HostedChannel.OPEN)
    synchronized(wait(1000L))

    val bumpedAmount = 99000.msat + hcs1.lastCrossSignedState.initHostedChannel.htlcMinimumMsat
    val ws = master.PaymentMaster.data.payments(cmd1.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    assertTrue(ws.map(_.amount).sum == bumpedAmount)
    assertTrue(ws.size == 2)
  }
}
