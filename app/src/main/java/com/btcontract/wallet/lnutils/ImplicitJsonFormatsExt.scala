package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln.utils._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.PaymentAction
import fr.acinq.eclair.MilliSatoshi


object ImplicitJsonFormatsExt {
  implicit val totalStatSummaryFmt: JsonFormat[TotalStatSummary] = jsonFormat[MilliSatoshi, MilliSatoshi, MilliSatoshi, Long, TotalStatSummary](TotalStatSummary.apply, "fees", "received", "sent", "count")
  implicit val totalStatSummaryExtFmt: JsonFormat[TotalStatSummaryExt] = jsonFormat[Option[TotalStatSummary], Long, Long, TotalStatSummaryExt](TotalStatSummaryExt.apply, "summary", "from", "to")

  // LNURL and payment actions

  implicit object PaymentActionFmt extends JsonFormat[PaymentAction] {
    def write(internal: PaymentAction): JsValue = internal match {
      case paymentAction: MessageAction => paymentAction.toJson
      case paymentAction: UrlAction => paymentAction.toJson
      case paymentAction: AESAction => paymentAction.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): PaymentAction = raw.asJsObject fields TAG match {
      case JsString("message") => raw.convertTo[MessageAction]
      case JsString("aes") => raw.convertTo[AESAction]
      case JsString("url") => raw.convertTo[UrlAction]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val aesActionFmt: JsonFormat[AESAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, String, AESAction](AESAction.apply, "domain", "description", "ciphertext", "iv"), tag = "aes")
  implicit val messageActionFmt: JsonFormat[MessageAction] = taggedJsonFmt(jsonFormat[Option[String], String, MessageAction](MessageAction.apply, "domain", "message"), tag = "message")
  implicit val urlActionFmt: JsonFormat[UrlAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, UrlAction](UrlAction.apply, "domain", "description", "url"), tag = "url")

  implicit object LNUrlDataFmt extends JsonFormat[LNUrlData] {
    def write(internal: LNUrlData): JsValue = throw new RuntimeException
    def read(raw: JsValue): LNUrlData = raw.asJsObject fields TAG match {
      case JsString("withdrawRequest") => raw.convertTo[WithdrawRequest]
      case JsString("payRequest") => raw.convertTo[PayRequest]
      case tag => throw new Exception(s"Unknown lnurl=$tag")
    }
  }

  // Note: tag on these MUST start with lower case because it is defined that way on protocol level
  implicit val withdrawRequestFmt: JsonFormat[WithdrawRequest] = taggedJsonFmt(jsonFormat[String, String, Long, String, Option[Long],
    WithdrawRequest](WithdrawRequest.apply, "callback", "k1", "maxWithdrawable", "defaultDescription", "minWithdrawable"), tag = "withdrawRequest")

  implicit val payRequestFmt: JsonFormat[PayRequest] = taggedJsonFmt(jsonFormat[String, Long, Long, String, Option[Int],
    PayRequest](PayRequest.apply, "callback", "maxSendable", "minSendable", "metadata", "commentAllowed"), tag = "payRequest")

  implicit val payRequestFinalFmt: JsonFormat[PayRequestFinal] = jsonFormat[Option[PaymentAction], Option[Boolean], Vector[String], String,
    PayRequestFinal](PayRequestFinal.apply, "successAction", "disposable", "routes", "pr")

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

  implicit val exampleAddonFmt: JsonFormat[ExampleAddon] = taggedJsonFmt(jsonFormat[Option[String], String, String, ExampleAddon](ExampleAddon.apply, "authToken", "supportEmail", "domain"), tag = "ExampleAddon")
  implicit val usedAddonsFmt: JsonFormat[UsedAddons] = jsonFormat[List[Addon], UsedAddons](UsedAddons.apply, "addons")
}
