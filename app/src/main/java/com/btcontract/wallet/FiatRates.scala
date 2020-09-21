package com.btcontract.wallet

import com.btcontract.wallet.FiatRates._
import com.btcontract.wallet.ln.crypto.Tools._
import com.github.kevinsawicki.http.HttpRequest._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import rx.lang.scala.{Subscription, Observable => Obs}
import com.btcontract.wallet.ln.RxUtils
import fr.acinq.eclair.secureRandom


object FiatRates {
  type Rates = Map[String, Double]
  type BitpayItemList = List[BitpayItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
  type BlockchainInfoItemMap = Map[String, BlockchainInfoItem]
  var subscription: Subscription = _
  var ratesInfo: RatesInfo = _

  val fiatNames: Map[String, String] =
    Map("usd" -> "US Dollar", "eur" -> "Euro", "jpy" -> "Japanese Yen", "cny" -> "Chinese Yuan",
      "inr" -> "Indian Rupee", "cad" -> "Canadian Dollar", "rub" -> "Русский Рубль", "brl" -> "Real Brasileiro",
      "gbp" -> "Pound Sterling", "aud" -> "Australian Dollar")

  def reloadData: Map[String, Double] = secureRandom nextInt 3 match {
    case 0 => to[CoinGecko](get("https://api.coingecko.com/api/v3/exchange_rates").body).rates.map { case code \ CoinGeckoItem(value) => code.toLowerCase -> value }
    case 1 => to[BlockchainInfoItemMap](get("https://blockchain.info/ticker").body).map { case code \ BlockchainInfoItem(last) => code.toLowerCase -> last }
    case _ => to[Bitpay](get("https://bitpay.com/rates").body).data.map { case BitpayItem(code, rate) => code.toLowerCase -> rate }.toMap
  }

  def makeObservable(stamp: Long): Obs[Rates] =
    RxUtils.initDelay(RxUtils.retry(RxUtils.ioQueue.map(_ => reloadData),
      RxUtils.pickInc, times = 3 to 18 by 3), stamp, 60 * 1000 * 30)
}

case class CoinGeckoItem(value: Double)
case class BlockchainInfoItem(last: Double)
case class BitpayItem(code: String, rate: Double)

case class Bitpay(data: BitpayItemList)
case class CoinGecko(rates: CoinGeckoItemMap)

case class RatesInfo(rates: Rates, oldRates: Rates, stamp: Long) {
  def pctDifference(code: String): String = (rates get code, oldRates get code) match {
    case Some(fresh) \ Some(old) if fresh > old => s"<font color=#5B8F36>▲ ${Denomination.formatFiat format Denomination.pctChange(fresh, old).abs}%</font>"
    case Some(fresh) \ Some(old) if fresh < old => s"<font color=#E35646>▼ ${Denomination.formatFiat format Denomination.pctChange(fresh, old).abs}%</font>"
    case _ => new String
  }
}