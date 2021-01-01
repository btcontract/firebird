package com.btcontract.wallet.lnutils

import com.github.kevinsawicki.http.HttpRequest._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.lnutils.ImplicitJsonFormatsExt._
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import com.btcontract.wallet.ln.ChainLink
import com.btcontract.wallet.ln.utils.Rx
import rx.lang.scala.Subscription


case class BlockCypherHeight(height: Int)
case class BlockChainHeight(height: Int)

class HttpApiChainLink extends ChainLink { me =>
  private[this] var chainTips: List[Int] = List(0) // Make sure list is never empty

  private[this] var subscriptions: List[Subscription] = Nil // Stop on getting enough confirmations

  private[this] def groupedTips: Map[Int, Int] = chainTips.groupBy(identity).mapValues(_.size)

  private[this] def processEvidence(height: Int): Unit = if (chainTipCanBeTrusted) runAnd(stop) { for (lst <- listeners) lst.onTrustedChainTipKnown }

  override def chainTipCanBeTrusted: Boolean = groupedTips.exists { case (tip, size) => tip == currentChainTip && size > 1 }

  override def currentChainTip: Int = groupedTips.maxBy(_._2)._1

  override def start: Unit = {
    val blockChain = Rx.retry(Rx.ioQueue.map(_ => to[BlockChainHeight](get("https://blockchain.info/latestblock").body).height), Rx.pickInc, times = 2 to 6 by 2)
    val blockCypher = Rx.retry(Rx.ioQueue.map(_ => to[BlockCypherHeight](get("https://api.blockcypher.com/v1/btc/main").body).height), Rx.pickInc, times = 2 to 6 by 2)
    val blockStream = Rx.retry(Rx.ioQueue.map(_ => get("https://blockstream.info/api/blocks/tip/height").body.toInt), Rx.pickInc, times = 2 to 6 by 2)

    val s1 = blockChain.subscribe(height => runAnd(chainTips :+= height)(me processEvidence height), none)
    val s2 = blockCypher.subscribe(height => runAnd(chainTips :+= height)(me processEvidence height), none)
    val s3 = blockStream.subscribe(height => runAnd(chainTips :+= height)(me processEvidence height), none)
    subscriptions = List(s1, s2, s3)
  }

  override def stop: Unit = subscriptions.foreach(_.unsubscribe)
}
