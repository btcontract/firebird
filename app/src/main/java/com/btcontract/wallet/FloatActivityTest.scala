package com.btcontract.wallet

import android.os.Bundle
import android.view.View
import com.thefuntasty.hauler.{DragDirection, HaulerView, OnDragDismissedListener}

class FloatActivityTest extends WalletActivity {
  lazy val haulerView: HaulerView = findViewById(R.id.haulerView).asInstanceOf[HaulerView]

  def INIT(state: Bundle): Unit = {
    setContentView(R.layout.float_test)
    haulerView setOnDragDismissedListener new OnDragDismissedListener {
      def onDismissed(dragDirection: DragDirection): Unit = finishMe(null)
    }
  }
}
