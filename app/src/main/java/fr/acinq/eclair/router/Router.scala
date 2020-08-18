/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Satoshi
import scodec.bits.ByteVector


object Router {
  case class RouterConf(requestNodeAnnouncements: Boolean,
                        encodingType: EncodingType,
                        channelQueryChunkSize: Int,
                        searchMaxFeeBase: Satoshi,
                        searchMaxFeePct: Double,
                        firstPassMaxRouteLength: Int,
                        searchMaxCltv: CltvExpiryDelta,
                        searchRatioCltv: Double,
                        searchRatioChannelAge: Double,
                        searchRatioChannelCapacity: Double,
                        searchRatioSuccessScore: Double,
                        mppMinPartAmount: MilliSatoshi,
                        maxRoutesPerPart: Int)

  // @formatter:off
  case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
  case class PublicChannel(update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], ann: ChannelAnnouncement) {
    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) ann.nodeId1 else ann.nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PublicChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
  }
  // @formatter:on

  case class AssistedChannel(extraHop: ExtraHop, nextNodeId: PublicKey, htlcMaximum: MilliSatoshi)

  trait Hop {
    /** @return the id of the start node. */
    def nodeId: PublicKey

    /** @return the id of the end node. */
    def nextNodeId: PublicKey

    /**
      * @param amount amount to be forwarded.
      * @return total fee required by the current hop.
      */
    def fee(amount: MilliSatoshi): MilliSatoshi

    /** @return cltv delta required by the current hop. */
    def cltvExpiryDelta: CltvExpiryDelta
  }

  /**
    * A directed hop between two connected nodes using a specific channel.
    *
    * @param nodeId     id of the start node.
    * @param nextNodeId id of the end node.
    * @param lastUpdate last update of the channel used for the hop.
    */
  case class ChannelHop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate) extends Hop {
    override lazy val cltvExpiryDelta: CltvExpiryDelta = lastUpdate.cltvExpiryDelta

    override def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(lastUpdate.feeBaseMsat, lastUpdate.feeProportionalMillionths, amount)
  }

  /**
    * A directed hop between two trampoline nodes.
    * These nodes need not be connected and we don't need to know a route between them.
    * The start node will compute the route to the end node itself when it receives our payment.
    *
    * @param nodeId          id of the start node.
    * @param nextNodeId      id of the end node.
    * @param cltvExpiryDelta cltv expiry delta.
    * @param fee             total fee for that hop.
    */
  case class NodeHop(nodeId: PublicKey, nextNodeId: PublicKey, cltvExpiryDelta: CltvExpiryDelta, fee: MilliSatoshi) extends Hop {
    override def fee(amount: MilliSatoshi): MilliSatoshi = fee
  }

  case class RouteParams(maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta, ratios: WeightRatios, maxRoutesPerPart: Int) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = {
      // The payment fee must satisfy either the flat fee or the percentage fee, not necessarily both.
      maxFeeBase.max(amount * maxFeePct)
    }
  }

  case class RouteRequest(partId: ByteVector,
                          source: PublicKey,
                          target: PublicKey,
                          amount: MilliSatoshi,
                          maxFee: MilliSatoshi,
                          localEdge: GraphEdge,
                          ignoreNodes: Set[PublicKey],
                          ignoreChannels: Set[ChannelDesc],
                          routeParams: RouteParams)

  case class Route(hops: Seq[ChannelHop]) {
    require(hops.nonEmpty, "route cannot be empty")

    def fee(amount: MilliSatoshi): MilliSatoshi = {
      val amountToSend = hops.drop(1).reverse.foldLeft(amount) { case (amount1, hop) => amount1 + hop.fee(amount1) }
      amountToSend - amount
    }

    /** This method retrieves the channel update that we used when we built the route. */
    def getChannelUpdateForNode(nodeId: PublicKey): Option[ChannelUpdate] = hops.find(_.nodeId == nodeId).map(_.lastUpdate)

    def printNodes(): String = hops.map(_.nextNodeId).mkString("->")

    def printChannels(): String = hops.map(_.lastUpdate.shortChannelId).mkString("->")
  }

  case class RouteResponse(partId: ByteVector, amount: MilliSatoshi, routes: Seq[Route]) {
    require(routes.nonEmpty, "routes cannot be empty")
  }

  case class ShortChannelIdAndFlag(shortChannelId: ShortChannelId, flag: Long)

  case class Data(channels: Map[ShortChannelId, PublicChannel], avgFeeBase: MilliSatoshi, graph: DirectedGraph)

  def getDesc(u: ChannelUpdate, announcement: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, announcement.nodeId1, announcement.nodeId2) else ChannelDesc(u.shortChannelId, announcement.nodeId2, announcement.nodeId1)
  }
}