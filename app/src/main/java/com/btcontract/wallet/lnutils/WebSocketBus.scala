package com.btcontract.wallet.lnutils

import com.neovisionaries.ws.client._
import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentHashMap
import com.btcontract.wallet.ln.crypto.CanBeRepliedTo
import com.btcontract.wallet.ln.crypto.Tools.Bytes
import scala.collection.mutable
import scodec.bits.ByteVector


case class WebSocketAndHandler(key: String, ws: WebSocket, handler: CanBeRepliedTo) {
  def isOpen: Boolean = ws.getState == WebSocketState.OPEN
}

trait WebSocketMessage
case object WebSocketOpen extends WebSocketMessage
case object WebSocketDisconnected extends WebSocketMessage
case class WebSocketTextMessage(text: String) extends WebSocketMessage
case class WebSocketBinaryMessage(data: ByteVector) extends WebSocketMessage

object WebSocketBus {
  type JavaList = java.util.List[String]
  type JavaListMap = java.util.Map[String, JavaList]
  val workers: mutable.Map[String, WebSocketAndHandler] =
    new ConcurrentHashMap[String, WebSocketAndHandler].asScala

  def setConnection(wsh: WebSocketAndHandler): Unit = {
    // Remove all existing listeners and then disconnect
    forget(wsh.key)

    wsh.ws addListener new WebSocketAdapter {
      override def onConnected(ws: WebSocket, headers: JavaListMap): Unit = wsh.handler process WebSocketOpen
      override def onTextMessage(ws: WebSocket, message: String): Unit = wsh.handler process WebSocketTextMessage(message)
      override def onBinaryMessage(ws: WebSocket, binary: Bytes): Unit = wsh.handler process WebSocketBinaryMessage(ByteVector view binary)
      override def onDisconnected(ws: WebSocket, scf: WebSocketFrame, ccf: WebSocketFrame, cbs: Boolean): Unit = wsh.handler process WebSocketDisconnected
      override def onConnectError(ws: WebSocket, reason: WebSocketException): Unit = ws.disconnect
    }

    // Then, add to map and connect
    wsh.ws.connectAsynchronously
    workers(wsh.key) = wsh
  }

  def forget(key: String): Unit = {
    workers.get(key).foreach(_.ws.clearListeners)
    workers.get(key).foreach(_.ws.disconnect)
  }
}
