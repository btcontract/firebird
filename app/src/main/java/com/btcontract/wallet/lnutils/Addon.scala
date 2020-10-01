package com.btcontract.wallet.lnutils

import com.btcontract.wallet.ln.crypto.CanBeRepliedTo


object Addon {
  val REMOVED = "state-removed"
  val MINIMIZED = "state-minimized"
  val ACTIVE = "state-active"
}

trait Addon {
  def authToken: Option[String]
  def supportEmail: String
  def domain: String
}

trait AddonData {
  val view: Option[CanBeRepliedTo]
  val addon: Addon
}

case class UsedAddons(addons: List[Addon] = Nil)
case class BasicAddonData(view: Option[CanBeRepliedTo], addon: Addon) extends AddonData
case class ExampleAddon(authToken: Option[String], supportEmail: String, domain: String) extends Addon

