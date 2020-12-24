package com.btcontract.wallet.ln.fsm

import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Incoming, Transition}
import com.btcontract.wallet.ln.HostedChannel.SUSPENDED
import fr.acinq.bitcoin.Satoshi


// Successful resize may come from a different handler, client should always re-check if new capacity is OK
abstract class ChannelResizeHandler(cmd: HC_CMD_RESIZE, chan: HostedChannel) extends ChannelListener {
  def onResizingCarriedOutSuccessfully: Unit
  def onResizingSentToSuspendedChannel: Unit
  chan.listeners += this
  chan process cmd

  override def onBecome: PartialFunction[Transition, Unit] = {
    case (_, _, _, prevState, SUSPENDED) if SUSPENDED != prevState =>
      // Something went wrong while we were trying to resize a channel
      onResizingSentToSuspendedChannel
      chan.listeners -= this

      case(_, hc0: HostedCommits, hc1: HostedCommits, _, _)
        if hc0.resizeProposal.isDefined && hc1.resizeProposal.isEmpty =>
        // Previous state had resizing proposal while new one does not
        onResizingCarriedOutSuccessfully
        chan.listeners -= this
  }

  override def onProcessSuccess: PartialFunction[Incoming, Unit] = {
    case (_, _, _: HC_CMD_RESIZE) if SUSPENDED == chan.state =>
      // We have sent a proposal to SUSPENDED channel
      onResizingSentToSuspendedChannel
      chan.listeners -= this
  }
}
