package com.btcontract.wallet.helper

import android.graphics.Color._
import androidx.core.graphics.ColorUtils


object Utils {
  def contrastedTextColor(color: Int): Int = {
    val whiteContrast = ColorUtils.calculateContrast(WHITE, color)
    val blackContrast = ColorUtils.calculateContrast(BLACK, color)
    if (whiteContrast > blackContrast * 3.75) BLACK else WHITE
  }
}
