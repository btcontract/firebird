package com.btcontract.wallet

import com.btcontract.wallet.R.string._
import com.btcontract.wallet.ln.ChanErrorCodes
import android.os.Bundle


class ChanDetailsActivity extends FirebirdActivity {
  lazy val chanErrorCodesMap: Map[String, Int] = Map (
    ChanErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY -> err_hosted_wrong_blockday,
    ChanErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG -> err_hosted_wrong_local_sig,
    ChanErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG -> err_hosted_wrong_remote_sig,
    ChanErrorCodes.ERR_HOSTED_CLOSED_BY_REMOTE_PEER -> err_hosted_closed_by_remote_peer,
    ChanErrorCodes.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC -> err_hosted_timed_out_outgoing_htlc,
    ChanErrorCodes.ERR_HOSTED_HTLC_EXTERNAL_FULFILL -> err_hosted_htlc_external_fulfill,
    ChanErrorCodes.ERR_HOSTED_CHANNEL_DENIED -> err_hosted_channel_denied,
    ChanErrorCodes.ERR_HOSTED_MANUAL_SUSPEND -> err_hosted_manual_suspend,
    ChanErrorCodes.ERR_MISSING_CHANNEL -> err_hosted_missing_channel
  )

  def INIT(state: Bundle): Unit = ???
}
