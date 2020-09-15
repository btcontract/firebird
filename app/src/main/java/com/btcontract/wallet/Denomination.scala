package com.btcontract.wallet

import java.text._
import fr.acinq.eclair._
import com.btcontract.wallet.R.string._
import com.btcontract.wallet.Denomination._
import language.implicitConversions


object Denomination {
  val locale = new java.util.Locale("en", "US")
  val symbols = new DecimalFormatSymbols(locale)
  val formatFiat = new DecimalFormat("#,###,###.##")

  symbols.setDecimalSeparator('.')
  symbols.setGroupingSeparator('\u00A0')
  formatFiat setDecimalFormatSymbols symbols

  def btcBigDecimal2MSat(btc: BigDecimal): MilliSatoshi =
    (btc * BtcDenomination.factor).toLong.msat
}

trait Denomination { me =>
  def rawString2MSat(raw: String): MilliSatoshi = (BigDecimal(raw) * factor).toLong.msat
  def asString(msat: MilliSatoshi): String = fmt.format(BigDecimal(msat.toLong) / factor)
  def parsedWithSign(msat: MilliSatoshi): String = parsed(msat) + sign
  protected def parsed(msat: MilliSatoshi): String

  def coloredOut(msat: MilliSatoshi, suffix: String) = s"<font color=#E35646><tt>-</tt>${me parsed msat}</font>$suffix"
  def coloredIn(msat: MilliSatoshi, suffix: String) = s"<font color=#6AAB38><tt>+</tt>${me parsed msat}</font>$suffix"

  val amountInTxt: String
  val fmt: DecimalFormat
  val factor: Long
  val sign: String
}

object SatDenomination extends Denomination {
  val fmt: DecimalFormat = new DecimalFormat("###,###,###.###")
  val amountInTxt: String = WalletApp.app.getString(amount_hint_sat)
  val sign = "\u00A0sat"
  val factor = 1000L

  fmt setDecimalFormatSymbols symbols
  def parsed(msat: MilliSatoshi): String = {
    val basicFormattedSum = asString(msat)
    val dotIndex = basicFormattedSum.indexOf(".")
    val (whole, decimal) = basicFormattedSum.splitAt(dotIndex)
    if (decimal == basicFormattedSum) basicFormattedSum
    else s"$whole<small>$decimal</small>"
  }
}

object BtcDenomination extends Denomination {
  val fmt: DecimalFormat = new DecimalFormat("##0.00000000###")
  val amountInTxt: String = WalletApp.app.getString(amount_hint_btc)
  val factor = 100000000000L
  val sign = "\u00A0btc"

  fmt setDecimalFormatSymbols symbols
  def parsed(msat: MilliSatoshi): String =
    asString(msat) take 10
}