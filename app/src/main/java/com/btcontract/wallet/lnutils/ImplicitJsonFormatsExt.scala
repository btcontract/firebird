package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import fr.acinq.eclair.MilliSatoshi


object ImplicitJsonFormatsExt {
  implicit val blockCypherHeightFmt: JsonFormat[BlockCypherHeight] = jsonFormat[Int, BlockCypherHeight](BlockCypherHeight.apply, "height")
  implicit val blockChainHeightFmt: JsonFormat[BlockChainHeight] = jsonFormat[Int, BlockChainHeight](BlockChainHeight.apply, "height")

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
