package com.btcontract.wallet.lnutils

import org.bitcoinj.core.listeners.{PeerConnectedEventListener, PeerDisconnectedEventListener}
import org.bitcoinj.core.{NetworkParameters, Peer, PeerGroup}
import org.bitcoinj.net.discovery.MultiplexingDiscovery
import com.btcontract.wallet.ln.ChainLink


class BitcoinJChainLink(params: NetworkParameters) extends ChainLink {
  val peerGroup = new PeerGroup(params)
  val maxPeers = 3

  private val peersListener = new PeerConnectedEventListener with PeerDisconnectedEventListener {
    def onPeerDisconnected(peer: Peer, leftPeers: Int): Unit = if (leftPeers < 1) for (lst <- listeners) lst.onCompleteChainDisconnect
    def onPeerConnected(peer: Peer, nowPeers: Int): Unit = if (chainTipCanBeTrusted) for (lst <- listeners) lst.onChainTipConfirmed
  }

  def chainTipCanBeTrusted: Boolean = peerGroup.numConnectedPeers >= maxPeers
  def currentChainTip: Int = peerGroup.getMostCommonChainHeight
  def stop: Unit = peerGroup.stopAsync

  def start: Unit = {
    peerGroup addPeerDiscovery MultiplexingDiscovery.forServices(params, 0)
    peerGroup addDisconnectedEventListener peersListener
    peerGroup addConnectedEventListener peersListener
    peerGroup setDownloadTxDependencies 0
    peerGroup setMaxConnections maxPeers
    peerGroup setPingIntervalMsec 10000
    peerGroup.startAsync
  }
}
