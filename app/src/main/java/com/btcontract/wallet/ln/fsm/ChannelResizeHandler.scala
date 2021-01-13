package com.btcontract.wallet.ln.fsm

import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.ChannelListener.{Incoming, Transition}
import com.btcontract.wallet.ln.HostedChannel.SUSPENDED
import fr.acinq.bitcoin.Satoshi


// Successful resize may come from a different handler, client should always re-check if new capacity is OK
abstract class ChannelResizeHandler(delta: Satoshi, chan: HostedChannel) extends ChannelListener { me =>
  def onResizingSuccessful(hc1: HostedCommits): Unit
  def onChannelSuspended(hc1: HostedCommits): Unit

  override def onBecome: PartialFunction[Transition, Unit] = {
    case (_, _, hc1: HostedCommits, prevState, SUSPENDED) if SUSPENDED != prevState =>
      // Something went wrong while we were trying to resize a channel
      onChannelSuspended(hc1)
      chan.listeners -= me

    case(_, hc0: HostedCommits, hc1: HostedCommits, _, _) if hc0.resizeProposal.isDefined && hc1.resizeProposal.isEmpty =>
      // Previous state had resizing proposal while new one does not and channel is not suspended, meaning it's all fine
      onResizingSuccessful(hc1)
      chan.listeners -= me
  }

  override def onProcessSuccess: PartialFunction[Incoming, Unit] = {
    case (_, hc1: HostedCommits, _: HC_CMD_RESIZE) if SUSPENDED == chan.state =>
      // We have sent a proposal to an already SUSPENDED channel
      onChannelSuspended(hc1)
      chan.listeners -= me
  }

  chan.listeners += me
  chan process HC_CMD_RESIZE(delta)
}
