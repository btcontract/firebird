package com.btcontract.wallet.lnutils


trait Addon {
  def authToken: Option[String]
  def supportEmail: String
  def domain: String
}

case class UsedAddons(addons: List[Addon] = Nil)
case class ExampleAddon(authToken: Option[String], supportEmail: String, domain: String) extends Addon