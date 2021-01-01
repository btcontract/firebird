package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet._
import com.btcontract.wallet.FiatRates._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.crypto.Tools.Fiat2Btc


object ImplicitJsonFormatsExt {
  implicit val blockCypherHeightFmt: JsonFormat[BlockCypherHeight] = jsonFormat[Int, BlockCypherHeight](BlockCypherHeight.apply, "height")
  implicit val blockChainHeightFmt: JsonFormat[BlockChainHeight] = jsonFormat[Int, BlockChainHeight](BlockChainHeight.apply, "height")

  // Fiat feerates

  implicit val ratesInfoFmt: JsonFormat[RatesInfo] = jsonFormat[Fiat2Btc, Fiat2Btc, Long, RatesInfo](RatesInfo.apply, "rates", "oldRates", "stamp")
  implicit val BlockchainInfoItemFmt: JsonFormat[BlockchainInfoItem] = jsonFormat[Double, BlockchainInfoItem](BlockchainInfoItem.apply, "last")
  implicit val bitpayItemFmt: JsonFormat[BitpayItem] = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt: JsonFormat[CoinGeckoItem] = jsonFormat[Double, CoinGeckoItem](CoinGeckoItem.apply, "value")
  implicit val coinGeckoFmt: JsonFormat[CoinGecko] = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt: JsonFormat[Bitpay] = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")

  // Addons

  implicit object AddonFmt extends JsonFormat[Addon] {
    def write(internal: Addon): JsValue = internal match {
      case exampleAddon: ExampleAddon => exampleAddon.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): Addon = raw.asJsObject fields TAG match {
      case JsString("ExampleAddon") => raw.convertTo[ExampleAddon]
      case tag => throw new Exception(s"Unknown addon=$tag")
    }
  }

  implicit val exampleAddonFmt: JsonFormat[ExampleAddon] = taggedJsonFmt(jsonFormat[Option[String], String, String,
    ExampleAddon](ExampleAddon.apply, "authToken", "supportEmail", "domain"), tag = "ExampleAddon")

  implicit val usedAddonsFmt: JsonFormat[UsedAddons] =
    jsonFormat[List[Addon], UsedAddons](UsedAddons.apply, "addons")
}
