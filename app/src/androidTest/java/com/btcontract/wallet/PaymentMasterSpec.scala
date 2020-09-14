package com.btcontract.wallet

import fr.acinq.eclair._
import com.softwaremill.quicklens._
import com.btcontract.wallet.SyncSpec._
import com.btcontract.wallet.GraphSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.lnutils.{SQliteChannelBag, SQliteNetworkDataStore}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.channel.CMD_SOCKET_OFFLINE
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress, NodeAnnouncement}
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector

@RunWith(classOf[AndroidJUnit4])
class PaymentMasterSpec {
  LNParams.routerConf = LNParams.routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L), firstPassMaxCltv = CltvExpiryDelta(1008 + 504))
  LNParams.keys = LightningNodeKeys.makeFromSeed(Tools.random.getBytes(32))

  def makeHostedCommits(nodeId: PublicKey, alias: String): HostedCommits = {
    val announce = Tools.mkNodeAnnouncement(nodeId, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), alias)
    val spec = CommitmentSpec(feeratePerKw = 0L, toLocal = 100000000L.msat, toRemote = 100000000L.msat)
    val init_hosted_channel = InitHostedChannel(UInt64(200000000L), 10.msat, 20, 200000000L.msat, 5000, 1000000.sat, 0.msat, ByteVector.empty)
    val lcss: LastCrossSignedState = LastCrossSignedState(refundScriptPubKey = randomBytes(119), init_hosted_channel, blockDay = 100, localBalanceMsat = 100000000L.msat, remoteBalanceMsat = 100000000L.msat,
      localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)
    HostedCommits(NodeAnnouncementExt(announce), lastCrossSignedState = lcss, futureUpdates = Vector.empty, localSpec = spec, updateOpt = None, brandingOpt = None, localError = None,
      remoteError = None, startedAt = System.currentTimeMillis)
  }

  @Test
  def splitAfterNoRouteFound(): Unit = {
    val store: SQliteNetworkDataStore = getRandomStore
    fillBasicGraph(store)
    val pf = new PathFinder(store, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val hcs = makeHostedCommits(nodeId = a, alias = "peer1")
    val channelBag = new SQliteChannelBag(store.db)
    channelBag.put(ByteVector32(hcs.announce.na.nodeId.value.take(32)), hcs)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl)

    val initialSendable = master.PaymentMaster.getSendable(master.all).values.head
    assertTrue(initialSendable == master.PaymentMaster.feeFreeBalance(master.all.head.chanAndCommitsOpt.get))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(1000L))

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
    assertTrue(w1.flight.get.route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, c, d))
    assertTrue(w2.flight.get.route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, b, d))

    val finalSendable = master.PaymentMaster.getSendable(master.all).values.head
    // Sendable was decreased by total payment amount with fees, but slightly increased relatively because 1% of the rest of sendable is smaller than initial 1% of balance
    println(finalSendable.truncateToSatoshi == (initialSendable - w1.amountWithFees - w2.amountWithFees + (w1.amountWithFees + w2.amountWithFees) * getParams.maxFeePct).truncateToSatoshi)
  }

  @Test
  def failAfterTooManyLocalErrors(): Unit = {
    val store: SQliteNetworkDataStore = getRandomStore
    fillBasicGraph(store)
    val pf = new PathFinder(store, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1").modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(UInt64(10)) // Payments will fail locally
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2").modify(_.lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat).setTo(UInt64(10)) // Payments will fail locally
    val channelBag = new SQliteChannelBag(store.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl)

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(1000L))

    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).data.inFlights.isEmpty)
    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).state == PaymentMaster.ABORTED)
  }

  @Test
  def chanBecomesOfflineAnotherIsUsed(): Unit = {
    val store: SQliteNetworkDataStore = getRandomStore
    fillBasicGraph(store)
    val pf = new PathFinder(store, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    val channelBag = new SQliteChannelBag(store.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl)

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
    val store: SQliteNetworkDataStore = getRandomStore
    fillBasicGraph(store)
    val pf = new PathFinder(store, LNParams.routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val hcs1 = makeHostedCommits(nodeId = a, alias = "peer1")
    val hcs2 = makeHostedCommits(nodeId = b, alias = "peer2")
    val channelBag = new SQliteChannelBag(store.db)
    channelBag.put(ByteVector32(hcs1.announce.na.nodeId.value.take(32)), hcs1)
    channelBag.put(ByteVector32(hcs2.announce.na.nodeId.value.take(32)), hcs2)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl)

    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    val desc = ChannelDesc(ShortChannelId(3L), b, d)
    master.PaymentMaster.data = master.PaymentMaster.data.copy(chanFailedAtAmount = Map(desc -> 200000L.msat))

    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(1000L))

    val ws = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.map(_.asInstanceOf[WaitForRouteOrInFlight])
    // A single 600 payment has been split into three payments such that failed at 200 channel can handle one of the parts
    assertTrue(ws.map(_.amount).toList.sorted == List(150000.msat, 150000.msat, 300000.msat))
  }
}
