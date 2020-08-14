package com.btcontract.wallet.helper

import java.text.{DecimalFormat, DecimalFormatSymbols}
import com.btcontract.wallet.ln.crypto.Tools.\
import fr.acinq.eclair.LongToBtcAmount
import fr.acinq.eclair.MilliSatoshi


object Denomination {
  val locale = new java.util.Locale("en", "US")
  val symbols = new DecimalFormatSymbols(locale)
  symbols.setGroupingSeparator('\u00A0')
  symbols.setDecimalSeparator('.')

  val formatFiat = new DecimalFormat("#,###,###.##")
  formatFiat setDecimalFormatSymbols symbols

  def btcBigDecimal2MSat(btc: BigDecimal): MilliSatoshi =
    (btc * BtcDenomination.factor).toLong.msat
}

trait Denomination { me =>
  def rawString2MSat(raw: String): MilliSatoshi = (BigDecimal(raw) * factor).toLong.msat
  def asString(msat: MilliSatoshi): String = fmt.format(BigDecimal(msat.toLong) / factor)
  def parsedWithSign(msat: MilliSatoshi): String = parsed(msat) + sign
  protected def parsed(msat: MilliSatoshi): String

  def coloredP2WSH(msat: MilliSatoshi, suffix: String): String = {
    val content = s"<font color=#0099FE>${this parsed msat}</font>$suffix"
    val start = "<tt><font color=#AAAAAA>[</font></tt>"
    val end = "<tt><font color=#AAAAAA>]</font></tt>"
    s"$start$content$end"
  }

  def coloredOut(msat: MilliSatoshi, suffix: String) = s"<font color=#E31300><tt>-</tt>${me parsed msat}</font>$suffix"
  def coloredIn(msat: MilliSatoshi, suffix: String) = s"<font color=#6AAB38><tt>+</tt>${me parsed msat}</font>$suffix"

  val fmt: DecimalFormat
  val factor: Long
  val sign: String
}

object SatDenomination extends Denomination {
  val fmt = new DecimalFormat("###,###,###.###")
  fmt setDecimalFormatSymbols Denomination.symbols
  val sign = "\u00A0sat"
  val factor = 1000L

  def parsed(msat: MilliSatoshi): String = {
    val basicFormattedMsatSum = asString(msat)
    val dotIndex = basicFormattedMsatSum.indexOf(".")
    val whole \ decimal = basicFormattedMsatSum.splitAt(dotIndex)
    if (decimal == basicFormattedMsatSum) basicFormattedMsatSum
    else s"$whole<small>$decimal</small>"
  }
}

object BtcDenomination extends Denomination {
  val fmt = new DecimalFormat("##0.00000000###")
  fmt setDecimalFormatSymbols Denomination.symbols
  val factor = 100000000000L
  val sign = "\u00A0btc"

  def parsed(msat: MilliSatoshi): String =
    asString(msat) take 10
}