package com.btcontract.wallet

import fr.acinq.eclair._
import com.btcontract.wallet.SyncSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.PaymentRequestExt
import com.btcontract.wallet.lnutils.{PaymentTable, SQlitePaymentBag}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentRequest
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector


@RunWith(classOf[AndroidJUnit4])
class PaymentDbSpec {
  @Test
  def handleManyPayments(): Unit = {
    val (normal, _) = getRandomStore
    val bag = new SQlitePaymentBag(normal.db)
    val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
    val prex = new PaymentRequestExt(PaymentRequest.read(ref), ref) { override def paymentHashStr: String = randomBytes32.toHex }

    {
      val a = System.currentTimeMillis()
      normal.db.txWrap {
        for (_ <- 0 to 25000) bag.addOutgoingPayment(randomKey.publicKey, prex, "Outgoing payment description", None, MilliSatoshi(10000000000L), MilliSatoshi(0L), Map.empty)
        for (_ <- 0 to 25000) bag.addIncomingPayment(prex, randomBytes32, "Incoming payment description", MilliSatoshi(10000000000L), MilliSatoshi(0L), Map.empty)
      }
      println("50000 inserts: " + (System.currentTimeMillis() - a))
    }

    {
      val a = System.currentTimeMillis()
      normal.db.txWrap {
        bag.listRecentPayments(25000).foreach(rc => bag.addSearchablePayment("Outgoing payment description", ByteVector32(ByteVector.fromValidHex(rc string PaymentTable.hash))))
      }
      println("listRecentPayments(25000) -> bag.addSearchablePayment: " + (System.currentTimeMillis() - a))
    }

    {
      val a = System.currentTimeMillis()
      bag.listRecentPayments(10).vec(bag.toPaymentInfo)
      println("listRecentPayments(10): " + (System.currentTimeMillis() - a))
    }

    {
      val a = System.currentTimeMillis()
      bag.listRecentPayments(100).vec(bag.toPaymentInfo)
      println("listRecentPayments(100): " + (System.currentTimeMillis() - a))
    }

    {
      val a = System.currentTimeMillis()
      bag.searchPayments("Outgoing").vec(bag.toPaymentInfo)
      println("searchPayments: " + (System.currentTimeMillis() - a))
    }

    {
      val a = System.currentTimeMillis()
      println(bag.betweenSummary(from = 0L, to = System.currentTimeMillis() + 1))
      println("bag.betweenSummary(from = 0L, to = System.currentTimeMillis() + 1): " + (System.currentTimeMillis() - a))
    }

    {
      val pub = bag.listRecentPayments(1).headTry(bag.toPaymentInfo).get.payeeNodeId
      val a = System.currentTimeMillis()
      bag.toNodeSummary(pub)
      println("bag.toNodeSummary(pub): " + (System.currentTimeMillis() - a))
    }

  }
}
