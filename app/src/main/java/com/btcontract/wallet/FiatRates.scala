package com.btcontract.wallet

import com.btcontract.wallet.FiatRates._
import com.btcontract.wallet.ln.crypto.Tools._
import com.github.kevinsawicki.http.HttpRequest._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.RxUtils
import rx.lang.scala.Observable


object FiatRates {
  type Rates = Map[String, Double]
  type BitpayItemList = List[BitpayItem]
  type CoinGeckoItemMap = Map[String, CoinGeckoItem]
  type BlockchainInfoItemMap = Map[String, BlockchainInfoItem]
  var ratesInfo: RatesInfo = _

  val fiatNames: Map[String, String] =
    Map("usd" -> "US Dollar", "eur" -> "Euro", "jpy" -> "Japanese Yen", "cny" -> "Chinese Yuan",
      "inr" -> "Indian Rupee", "cad" -> "Canadian Dollar", "rub" -> "Русский Рубль", "brl" -> "Real Brasileiro",
      "gbp" -> "Pound Sterling", "aud" -> "Australian Dollar")

  def reloadData: Map[String, Double] = random nextInt 3 match {
    case 0 => to[CoinGecko](get("https://api.coingecko.com/api/v3/exchange_rates").body).rates.map { case code \ CoinGeckoItem(value) => code.toLowerCase -> value }
    case 1 => to[BlockchainInfoItemMap](get("https://blockchain.info/ticker").body).map { case code \ BlockchainInfoItem(last) => code.toLowerCase -> last }
    case _ => to[Bitpay](get("https://bitpay.com/rates").body).data.map { case BitpayItem(code, rate) => code.toLowerCase -> rate }.toMap
  }

  def observable(stamp: Long): Observable[Rates] =
    RxUtils.initDelay(RxUtils.retry(RxUtils.ioQueue.map(_ => reloadData),
      RxUtils.pickInc, times = 3 to 18 by 3), stamp, 60 * 1000 * 30)
}

case class CoinGeckoItem(value: Double)
case class BlockchainInfoItem(last: Double)
case class BitpayItem(code: String, rate: Double)

case class Bitpay(data: BitpayItemList)
case class CoinGecko(rates: CoinGeckoItemMap)
case class RatesInfo(rates: Rates, oldRates: Rates, stamp: Long)