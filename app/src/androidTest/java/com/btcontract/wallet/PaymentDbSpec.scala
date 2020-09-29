package com.btcontract.wallet

import fr.acinq.eclair._
import com.btcontract.wallet.SyncSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.PaymentRequestExt
import com.btcontract.wallet.lnutils.{SQlitePaymentBag, TotalStatSummary}
import fr.acinq.eclair.payment.PaymentRequest
import org.junit.runner.RunWith
import org.junit.Test
import org.junit.Assert._

import scala.util.Try


@RunWith(classOf[AndroidJUnit4])
class PaymentDbSpec {
  @Test
  def handleCacheOnManyRecords(): Unit = {
    val (normal, _) = getRandomStore
    var summaryCalledTimes: Int = 0

    val bag = new SQlitePaymentBag(normal.db) {
      override def betweenSummary(from: Long, to: Long): Try[TotalStatSummary] = {
        summaryCalledTimes += 1
        super.betweenSummary(from, to)
      }
    }

    val cachedBag = new CachedSQlitePaymentBag(bag)
    val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
    val prex = new PaymentRequestExt(PaymentRequest.read(ref), ref) { override def paymentHashStr: String = randomBytes32.toHex }

    normal.db.txWrap {
      for (_ <- 0 to 2500) cachedBag.bag.addOutgoingPayment(randomKey.publicKey, prex, "Outgoing payment description", None, MilliSatoshi(10000000000L), MilliSatoshi(0L), Map.empty)
      for (_ <- 0 to 2500) cachedBag.bag.addIncomingPayment(prex, randomBytes32, "Incoming payment description", MilliSatoshi(10000000000L), MilliSatoshi(0L), Map.empty)
    }

    cachedBag.invlidateBoundaries(0L, Long.MaxValue)

    for (_ <- 0 to 10) cachedBag.getCurrent

    cachedBag.invlidateContent

    for (_ <- 0 to 10) cachedBag.getCurrent

    val cachedBag1 = new CachedSQlitePaymentBag(bag)

    for (_ <- 0 to 10) cachedBag1.getCurrent

    assertTrue(2 == summaryCalledTimes)

    {
      val a = System.currentTimeMillis
      for (_ <- 0 to 100) cachedBag.bag.betweenSummary(0L, Long.MaxValue)
      println(s"No cache: ${System.currentTimeMillis - a}")
    }

    {
      val cachedBag2 = new CachedSQlitePaymentBag(bag)
      val a = System.currentTimeMillis
      for (_ <- 0 to 100) cachedBag2.getCurrent
      println(s"With cache: ${System.currentTimeMillis - a}")
    }
  }
}
