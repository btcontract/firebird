package com.btcontract.wallet.ln.fsm

import fr.acinq.eclair._
import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Incoming, Transition}
import com.btcontract.wallet.ln.crypto.ChannelResizingAlreadyInProgress
import com.btcontract.wallet.ln.HostedChannel.SUSPENDED
import fr.acinq.bitcoin.Satoshi


abstract class ChannelResizeHandler(chan: HostedChannel, newCapacity: Satoshi) extends ChannelListener {
  val command: HC_CMD_RESIZE = HC_CMD_RESIZE(newCapacity, requestId = randomBytes32)
  chan.listeners += this
  chan process command

  def onResizingSentToSuspendedChannel: Unit
  def onResizingAlreadyInProgress: Unit
  def onResizingSuccess: Unit

  override def onBecome: PartialFunction[Transition, Unit] = {
    case (_, _, _, prevState, SUSPENDED) if SUSPENDED != prevState =>
      // Something went wrong while we were trying to resize a channel
      chan.listeners -= this

      case(_, hc0: HostedCommits, hc1: HostedCommits, _, _)
        if hc0.resizeProposal.isDefined && hc1.resizeProposal.isEmpty =>
        // Previous state had resizing proposal while new one does not
        chan.listeners -= this
        onResizingSuccess
  }

  override def onException: PartialFunction[Malfunction, Unit] = {
    case (_, err: ChannelResizingAlreadyInProgress) if err.cmd == command =>
      // We have sent a resize to a channel which is already being resized
      onResizingAlreadyInProgress
      chan.listeners -= this
  }

  override def onProcessSuccess: PartialFunction[Incoming, Unit] = {
    case (_, _, _: HC_CMD_RESIZE) if SUSPENDED == chan.state =>
      // We have sent a proposal to SUSPENDED channel
      onResizingSentToSuspendedChannel
      chan.listeners -= this
  }
}
