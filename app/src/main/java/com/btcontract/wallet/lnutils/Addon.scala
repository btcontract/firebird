package com.btcontract.wallet.lnutils

import com.neovisionaries.ws.client.WebSocket


trait Addon {
  def authToken: Option[String]
  def keyName: String
  def domain: String
}

trait WebSocketAddon extends Addon {
  def createWebSocket: WebSocket
}

case class UsedAddons(addons: List[Addon] = Nil) {
  def webSocketAddons: Seq[WebSocketAddon] = addons collect { case wsa: WebSocketAddon => wsa }
}

case class ExampleAddon(authToken: Option[String], domain: String, keyName: String) extends Addon