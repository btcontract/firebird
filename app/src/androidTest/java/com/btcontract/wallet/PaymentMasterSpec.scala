package com.btcontract.wallet

import fr.acinq.eclair._
import com.btcontract.wallet.SyncSpec._
import com.btcontract.wallet.GraphSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.lnutils.{SQliteChannelBag, SQliteNetworkDataStore}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId}
import fr.acinq.eclair.wire.{InitHostedChannel, LastCrossSignedState, NodeAddress, NodeAnnouncement}
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector

@RunWith(classOf[AndroidJUnit4])
class PaymentMasterSpec {
  LNParams.keys = LightningNodeKeys.makeFromSeed(Tools.random.getBytes(32))
  val store: SQliteNetworkDataStore = getStore

  @Test
  def splitAfterNoRouteFound(): Unit = {
    fillBasicGraph(store)
    val pf = new PathFinder(store, routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val announce = Tools.mkNodeAnnouncement(a, NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "peer")
    val spec = CommitmentSpec(feeratePerKw = 0L, toLocal = 100000000L.msat, toRemote = 100000000L.msat)
    val init_hosted_channel = InitHostedChannel(UInt64(200000000L), 10.msat, 20, 200000000L.msat, 5000, 1000000.sat, 0.msat, ByteVector.empty)
    val lcss: LastCrossSignedState = LastCrossSignedState(refundScriptPubKey = randomBytes(119), init_hosted_channel, blockDay = 100, localBalanceMsat = 100000000L.msat, remoteBalanceMsat = 100000000L.msat,
      localUpdates = 201, remoteUpdates = 101, incomingHtlcs = Nil, outgoingHtlcs = Nil, remoteSigOfLocal = ByteVector64.Zeroes, localSigOfRemote = ByteVector64.Zeroes)
    val hcs = HostedCommits(NodeAnnouncementExt(announce), lastCrossSignedState = lcss, futureUpdates = Vector.empty, localSpec = spec, updateOpt = None, brandingOpt = None, localError = None,
      remoteError = None, startedAt = System.currentTimeMillis)

    val channelBag = new SQliteChannelBag(db)
    channelBag.put(ByteVector32(announce.nodeId.value.take(32)), hcs)

    val cl = new BitcoinJChainLink(WalletApp.params)
    val dummyPaymentInfoBag = new PaymentInfoBag { def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = None }
    val master = new ChannelMaster(dummyPaymentInfoBag, channelBag, pf, cl)

    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = Long.MaxValue.msat)
    val cmd = CMD_SEND_MPP(paymentHash = ByteVector32.Zeroes, totalAmount = 600000.msat, targetNodeId = s, paymentSecret = ByteVector32.Zeroes, targetExpiry = CltvExpiry(9), assistedEdges = Set(edgeDSFromD))
    master.PaymentMaster process cmd
    synchronized(wait(1000L))

    // Our only channel is offline
    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.head.asInstanceOf[WaitForBetterConditions].amount == cmd.totalAmount)
    master.all.foreach(chan => chan.BECOME(chan.data, HostedChannel.OPEN))
    synchronized(wait(500L))

    // Graph is not yet ready
    assertTrue(master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.head.asInstanceOf[WaitForRouteOrInFlight].amount == cmd.totalAmount)
    assertTrue(pf.data.extraEdges.size == 1)
    pf process PathFinder.CMDLoadGraph
    synchronized(wait(500L))

    // First route is now overloaded, so another one is chosen
    val List(w1, w2) = master.PaymentMaster.data.payments(cmd.paymentHash).data.parts.values.toList
    assertTrue(w1.asInstanceOf[WaitForRouteOrInFlight].flight.get.route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, c, d))
    assertTrue(w2.asInstanceOf[WaitForRouteOrInFlight].flight.get.route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, b, d))
  }
}
