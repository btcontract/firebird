package com.btcontract.wallet

import spray.json._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.lnutils.ImplicitJsonFormatsExt._
import com.btcontract.wallet.ln.crypto.Tools.{Fiat2Btc, none}
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats.to
import com.btcontract.wallet.ln.utils.{Denomination, Rx}
import com.github.kevinsawicki.http.HttpRequest.get
import android.content.SharedPreferences
import rx.lang.scala.Subscription
import scala.util.Try


object FiatRates {
  type BitpayItemList = List[BitpayItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
  type BlockchainInfoItemMap = Map[String, BlockchainInfoItem]
}

class FiatRates(preferences: SharedPreferences) {
  def reloadData: Fiat2Btc = fr.acinq.eclair.secureRandom nextInt 3 match {
    case 0 => to[CoinGecko](get("https://api.coingecko.com/api/v3/exchange_rates").body).rates.map { case (code, item) => code.toLowerCase -> item.value }
    case 1 => to[FiatRates.BlockchainInfoItemMap](get("https://blockchain.info/ticker").body).map { case (code, item) => code.toLowerCase -> item.last }
    case _ => to[Bitpay](get("https://bitpay.com/rates").body).data.map { case BitpayItem(code, rate) => code.toLowerCase -> rate }.toMap
  }

  var ratesInfo: RatesInfo = Try {
    preferences.getString(WalletApp.FIAT_RATES_DATA, new String)
  } map to[RatesInfo] getOrElse RatesInfo(Map.empty, Map.empty, stamp = 0L)

  private[this] val periodSecs = 60 * 1000 * 30

  private[this] val retryRepeatDelayedCall = {
    val retry = Rx.retry(Rx.ioQueue.map(_ => reloadData), Rx.pickInc, 3 to 18 by 3)
    val repeat = Rx.repeat(retry, Rx.pickInc, periodSecs to Int.MaxValue by periodSecs)
    Rx.initDelay(repeat, ratesInfo.stamp, periodSecs)
  }

  val listeners: Set[FiatRatesListener] = Set.empty

  val subscription: Subscription = retryRepeatDelayedCall.subscribe(newRates => {
    val newRatesInfo = RatesInfo(newRates, oldRates = ratesInfo.rates, stamp = System.currentTimeMillis)
    preferences.edit.putString(WalletApp.FIAT_RATES_DATA, newRatesInfo.toJson.compactPrint).commit
    for (lst <- listeners) lst.onFiatRates(newRatesInfo)
    ratesInfo = newRatesInfo
  }, none)
}

trait FiatRatesListener {
  def onFiatRates(rates: RatesInfo): Unit
}

case class CoinGeckoItem(value: Double)
case class BlockchainInfoItem(last: Double)
case class BitpayItem(code: String, rate: Double)

case class Bitpay(data: FiatRates.BitpayItemList)
case class CoinGecko(rates: FiatRates.CoinGeckoItemMap)

case class RatesInfo(rates: Fiat2Btc, oldRates: Fiat2Btc, stamp: Long) {
  def pctChange(fresh: Double, old: Double): Double = (fresh - old) / old * 100
  def pctDifference(code: String): String = List(rates get code, oldRates get code) match {
    case Some(fresh) :: Some(old) :: Nil if fresh > old => s"<font color=#5B8F36>▲ ${Denomination.formatFiat format pctChange(fresh, old).abs}%</font>"
    case Some(fresh) :: Some(old) :: Nil if fresh < old => s"<font color=#E35646>▼ ${Denomination.formatFiat format pctChange(fresh, old).abs}%</font>"
    case _ => new String
  }
}