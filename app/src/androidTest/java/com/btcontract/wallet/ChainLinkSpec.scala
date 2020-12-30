package com.btcontract.wallet

import org.junit.Assert._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.ChainLinkListener
import com.btcontract.wallet.lnutils.{BitcoinJChainLink, FallbackChainLink}
import com.btcontract.wallet.ln.crypto.Tools.none
import org.junit.runner.RunWith
import org.junit.Test


@RunWith(classOf[AndroidJUnit4])
class ChainLinkSpec {
  @Test
  def chainLinksWork(): Unit = {
    val cl1 = new FallbackChainLink
    val cl2 = new BitcoinJChainLink(org.bitcoinj.params.MainNetParams.get)
    var cl1Result: Int = 1
    var cl2Result: Int = 2

    cl1 addAndMaybeInform new ChainLinkListener {
      override def onCompleteChainDisconnect: Unit = none
      override def onChainTipConfirmed: Unit = {
        println(s"Fallback: ${cl1.currentChainTip}")
        cl1Result = cl1.currentChainTip
      }
    }

    cl2 addAndMaybeInform new ChainLinkListener {
      override def onCompleteChainDisconnect: Unit = none
      override def onChainTipConfirmed: Unit = {
        println(s"Chain: ${cl2.currentChainTip}")
        cl2Result = cl2.currentChainTip
      }
    }

    cl1.start
    cl2.start
    synchronized(wait(10000L))
    assertTrue(cl1Result == cl2Result)
  }
}
