package com.btcontract.wallet

import com.btcontract.wallet.ln._
import com.btcontract.wallet.R.string._
import com.aurelhubert.ahbottomnavigation._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.fsm.OpenHandler
import com.btcontract.wallet.ln.utils.LNUrl
import org.bitcoinj.uri.BitcoinURI
import android.widget.FrameLayout
import android.content.ClipData
import android.os.Bundle
import scala.util.Try
import java.util


class HubActivity extends FirebirdActivity with AHBottomNavigation.OnTabSelectedListener { me =>
  lazy val bottomNavigation: AHBottomNavigation = findViewById(R.id.bottomNavigation).asInstanceOf[AHBottomNavigation]
  lazy val contentWindow: FrameLayout = findViewById(R.id.contentWindow).asInstanceOf[FrameLayout]

  lazy val chanErrorCodesMap: Map[String, Int] = Map (
    HCErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY -> err_hosted_wrong_blockday,
    HCErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG -> err_hosted_wrong_local_sig,
    HCErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG -> err_hosted_wrong_remote_sig,
    HCErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER -> err_hosted_closed_by_remote_peer,
    HCErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC -> err_hosted_timed_out_outgoing_htlc,
    HCErrorCodes.ERR_HOSTED_HTLC_EXTERNAL_FULFILL -> err_hosted_htlc_external_fulfill,
    HCErrorCodes.ERR_HOSTED_CHANNEL_DENIED -> err_hosted_channel_denied,
    HCErrorCodes.ERR_HOSTED_MANUAL_SUSPEND -> err_hosted_manual_suspend,
    HCErrorCodes.ERR_HOSTED_INVALID_RESIZE -> err_hosted_invalid_resize,
    HCErrorCodes.ERR_MISSING_CHANNEL -> err_hosted_missing_channel
  )

  override def onResume: Unit = {
    // initChannelsOnTipKnownIfHasOutstanding
    checkCurrentClipboard
    super.onResume
  }

  def INIT(state: Bundle): Unit =
    if (WalletApp.isOperational) {
      setContentView(R.layout.activity_hub)
      val wallet = new AHBottomNavigationItem(item_wallet, R.drawable.ic_wallet_black_24dp, R.color.accent, "wallet")
      val shopping = new AHBottomNavigationItem(item_shopping, R.drawable.ic_shopping_black_24dp, R.color.accent, "shopping")
      val addons = new AHBottomNavigationItem(item_addons, R.drawable.ic_add_black_24dp, R.color.accent, "addons")
      bottomNavigation addItems util.Arrays.asList(wallet, shopping, addons)
      bottomNavigation setOnTabSelectedListener me
    } else me exitTo classOf[MainActivity]

  def initChannelsOnTipKnownIfHasOutstanding: Unit =
    if (LNParams.format.outstandingProviders.nonEmpty) {
      // Initialize this operation AFTER chain tip becomes known
      LNParams.channelMaster.cl addAndMaybeInform new ChainLinkListener {
        override def onTrustedChainTipKnown: Unit = initChannelsOnTipKnown
        override def onCompleteChainDisconnect: Unit = none
        override val isTransferrable = true
      }
    }

  def initChannelsOnTipKnown: Unit =
    LNParams.format.outstandingProviders foreach {
      case ann if LNParams.channelMaster.fromNode(ann.nodeId).isEmpty =>
        new OpenHandler(NodeAnnouncementExt(ann), LNParams.hcInit, LNParams.format, LNParams.channelMaster) {
          override def onFailure(channel: HostedChannel, err: Throwable): Unit = UITask(WalletApp.app quickToast err.getMessage).run
          override def onEstablished(channel: HostedChannel): Unit = WalletApp.syncRmOutstanding(channel.data.announce.na)
          override def onDisconnect(worker: CommsTower.Worker): Unit = CommsTower.forget(worker.pkap)
        }

      case hasChannelAnn =>
        // This hosted channel already exists
        WalletApp.syncRmOutstanding(hasChannelAnn)
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

        case _: LNUrl =>
          val message = getString(buffer_link_found)
          snack(contentWindow, message, dialog_view, _.dismiss)
          clearClipboard

        case _ =>
        // Do nothing
      }
    }

  def clearClipboard: Unit = WalletApp.app.clipboardManager setPrimaryClip ClipData.newPlainText(null, new String)

  def onTabSelected(position: Int, tag: String, wasSelected: Boolean): Boolean = true
}
