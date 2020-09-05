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
import fr.acinq.eclair.router.Graph.{GraphStructure, RichWeight}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


object Router {
  case class RouterConf(channelQueryChunkSize: Int,
                        searchMaxFeeBase: MilliSatoshi,
                        searchMaxFeePct: Double,
                        firstPassMaxRouteLength: Int,
                        firstPassMaxCltv: CltvExpiryDelta,
                        mppMinPartAmount: MilliSatoshi,
                        maxChannelFailures: Int,
                        maxStrangeNodeFailures: Int,
                        maxLocalAttempts: Int,
                        maxRemoteAttempts: Int)

  // @formatter:off
  case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
  case class PublicChannel(update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], ann: ChannelAnnouncement) {
    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) ann.nodeId1 else ann.nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PublicChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
  }
  // @formatter:on

  case class AssistedChannel(extraHop: ExtraHop, nextNodeId: PublicKey)

  case class RouteParams(maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = {
      // The payment fee must satisfy either the flat fee or the percentage fee, not necessarily both.
      maxFeeBase.max(amount * maxFeePct)
    }
  }

  case class RouteRequest(paymentHash: ByteVector32,
                          partId: ByteVector,
                          source: PublicKey,
                          target: PublicKey,
                          amount: MilliSatoshi,
                          maxFee: MilliSatoshi,
                          localEdge: GraphEdge,
                          routeParams: RouteParams,
                          ignoreNodes: Set[PublicKey] = Set.empty,
                          ignoreChannels: Set[ChannelDesc] = Set.empty) {

    // Used for "failed at amount" to avoid small delta retries (e.g. 1003 sat, 1002 sat, ...)
    lazy val reserve: MilliSatoshi = amount / 10
  }

  case class Route(weight: RichWeight, hops: Seq[GraphEdge] = Nil) {
    require(hops.nonEmpty, "route cannot be empty")

    lazy val fee: MilliSatoshi = weight.costs.head - weight.costs.last

    // We don't care bout first route and amount since they belong to local channels
    lazy val amountPerDescAndCap: Seq[(MilliSatoshi, GraphStructure.DescAndCapacity)] = weight.costs.tail.zip(hops.tail.map(_.toDescAndCapacity))

    /** This method retrieves the edge that we used when we built the route. */
    def getEdgeForNode(nodeId: PublicKey): Option[GraphEdge] = hops.find(_.desc.a == nodeId)
  }

  sealed trait RouteResponse { def paymentHash: ByteVector32 }
  case class RouteFound(paymentHash: ByteVector32, partId: ByteVector, route: Route) extends RouteResponse
  case class NoRouteAvailable(paymentHash: ByteVector32, partId: ByteVector) extends RouteResponse

  case class Data(channels: Map[ShortChannelId, PublicChannel], extraEdges: Map[ShortChannelId, GraphEdge], graph: DirectedGraph)

  def getDesc(u: ChannelUpdate, announcement: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, announcement.nodeId1, announcement.nodeId2) else ChannelDesc(u.shortChannelId, announcement.nodeId2, announcement.nodeId1)
  }
}