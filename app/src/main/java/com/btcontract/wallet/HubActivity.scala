package com.btcontract.wallet

import com.btcontract.wallet.ln._
import com.btcontract.wallet.R.string._
import com.aurelhubert.ahbottomnavigation._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SUSPENDED, WAIT_FOR_ACCEPT}
import fr.acinq.eclair.wire.{ChannelUpdate, HostedChannelMessage, LightningMessage}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import org.bitcoinj.uri.BitcoinURI
import android.widget.FrameLayout
import android.content.ClipData
import scodec.bits.ByteVector
import fr.acinq.eclair.wire
import android.os.Bundle
import scala.util.Try
import java.util


class HubActivity extends FirebirdActivity with AHBottomNavigation.OnTabSelectedListener { me =>
  lazy val bottomNavigation: AHBottomNavigation = findViewById(R.id.bottomNavigation).asInstanceOf[AHBottomNavigation]
  lazy val contentWindow: FrameLayout = findViewById(R.id.contentWindow).asInstanceOf[FrameLayout]

  override def onResume: Unit = {
    // initChannelsOnTipKnownIfHasOutstanding
    checkCurrentClipboard
    super.onResume
  }

  def INIT(state: Bundle): Unit =
    if (WalletApp.isOperational) {
      setContentView(R.layout.activity_hub)
      val wallet = new AHBottomNavigationItem(item_wallet, R.drawable.ic_wallet_black_24dp, R.color.accent, "wallet")
      val addons = new AHBottomNavigationItem(item_addons, R.drawable.ic_add_black_24dp, R.color.accent, "addons")
      bottomNavigation addItems util.Arrays.asList(wallet, addons)
      bottomNavigation setOnTabSelectedListener me
    } else me exitTo classOf[MainActivity]

  def initChannelsOnTipKnownIfHasOutstanding: Unit =
    if (LNParams.format.outstandingProviders.nonEmpty) {
      // Initialize this operation AFTER chain tip becomes known
      WalletApp.chainLink addAndMaybeInform new ChainLinkListener {
        def onChainTipKnown: Unit = initChannelsOnTipKnown
        def onTotalDisconnect: Unit = none
      }
    }

  def initChannelsOnTipKnown: Unit =
    LNParams.format.outstandingProviders foreach {
      case ann if LNParams.channelMaster.fromNode(ann.nodeId).isEmpty =>
        val peerSpecificSecret: ByteVector = LNParams.format.attachedChannelSecret
        val peerSpecificRefundPubKey: ByteVector = LNParams.format.keys.refundPubKey(ann.nodeId)
        val waitData = WaitRemoteHostedReply(NodeAnnouncementExt(ann), peerSpecificRefundPubKey, peerSpecificSecret)
        val freshChannel = LNParams.channelMaster.mkHostedChannel(initListeners = Set.empty, waitData)

        val makeChanListener = new ConnectionListener with ChannelListener {
          override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = freshChannel process msg
          override def onDisconnect(worker: CommsTower.Worker): Unit = CommsTower.forget(worker.pkap)

          override def onOperational(worker: CommsTower.Worker): Unit = {
            freshChannel process CMD_CHAIN_TIP_KNOWN
            freshChannel process CMD_SOCKET_ONLINE
          }

          override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = msg match {
            case update: ChannelUpdate => freshChannel process update
            case error: wire.Error => freshChannel process error
            case _ =>
          }

          override def onBecome: PartialFunction[Transition, Unit] = {
            case (_, _, newChannelData, WAIT_FOR_ACCEPT, OPEN | SUSPENDED) =>
              // Hosted channel is now established and stored, may contain error
              freshChannel.listeners = LNParams.channelMaster.operationalListeners
              CommsTower.listeners(newChannelData.announce.nodeSpecificPkap) -= this
              LNParams.channelMaster.all = LNParams.channelMaster.all :+ freshChannel
              // Add standard listener for this new channel
              LNParams.channelMaster.initConnect
              WalletApp syncRmOutstanding ann
          }

          override def onException: PartialFunction[Malfunction, Unit] = {
            // Something went wrong while trying to establish a channel, inform user
            case (_, err) => UITask(WalletApp.app quickToast err.getMessage).run
          }
        }

        // listen and connect right away
        val pkap = freshChannel.data.announce.nodeSpecificPkap
        CommsTower.listen(Set(makeChanListener), pkap, ann, LNParams.extInit)
        freshChannel.listeners += makeChanListener

      case hasChannelAnn =>
        // This hosted channel already exists
        WalletApp syncRmOutstanding hasChannelAnn
    }

  def checkCurrentClipboard: Unit =
    Try(WalletApp.app.getBufferUnsafe) foreach { content =>
      runInFutureProcessOnUI(WalletApp.parse(content), none) {

        case _: PaymentRequestExt =>
          val message = getString(buffer_invoice_found)
          snack(contentWindow, message, dialog_view, _.dismiss)
          clearClipboard

        case _: BitcoinURI =>
          val message = getString(buffer_address_found)
          snack(contentWindow, message, dialog_view, _.dismiss)
          clearClipboard

        case _ =>
        // Do nothing
      }
    }

  def clearClipboard: Unit = WalletApp.app.clipboardManager setPrimaryClip ClipData.newPlainText(null, new String)

  def onTabSelected(position: Int, tag: String, wasSelected: Boolean): Boolean = true
}
