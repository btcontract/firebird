package com.btcontract.wallet

import com.btcontract.wallet.R.string._
import com.aurelhubert.ahbottomnavigation._
import android.widget.FrameLayout
import android.os.Bundle
import java.util


class HubActivity extends FirebirdActivity with AHBottomNavigation.OnTabSelectedListener { me =>
  lazy val bottomNavigation: AHBottomNavigation = findViewById(R.id.bottomNavigation).asInstanceOf[AHBottomNavigation]
  lazy val contentWindow: FrameLayout = findViewById(R.id.contentWindow).asInstanceOf[FrameLayout]

  def INIT(state: Bundle): Unit =
    if (WalletApp.isOperational) {
      setContentView(R.layout.activity_hub)
      val wallet = new AHBottomNavigationItem(item_wallet, R.drawable.ic_wallet_black_24dp, R.color.accent, "wallet")
      val shopping = new AHBottomNavigationItem(item_shopping, R.drawable.ic_shopping_black_24dp, R.color.accent, "shopping")
      val addons = new AHBottomNavigationItem(item_addons, R.drawable.ic_add_black_24dp, R.color.accent, "addons")
      bottomNavigation addItems util.Arrays.asList(wallet, shopping, addons)
      bottomNavigation setOnTabSelectedListener me
    } else me exitTo classOf[MainActivity]

  def onTabSelected(position: Int, tag: String, wasSelected: Boolean): Boolean = true
}
