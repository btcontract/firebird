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

import java.util.UUID

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.wire._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

object Router {
  case class RouterConf(searchAttempts: Int,
                        nodeFailTolerance: Int,
                        requestNodeAnnouncements: Boolean,
                        encodingType: EncodingType,
                        channelRangeChunkSize: Int,
                        channelQueryChunkSize: Int,
                        searchMaxFeeBase: Satoshi,
                        searchMaxFeePct: Double,
                        firstPassMaxRouteLength: Int,
                        searchMaxCltv: CltvExpiryDelta,
                        searchHeuristicsEnabled: Boolean,
                        searchRatioCltv: Double,
                        searchRatioChannelAge: Double,
                        searchRatioChannelCapacity: Double,
                        searchRatioSuccessScore: Double,
                        mppMinPartAmount: MilliSatoshi,
                        mppMaxParts: Int)

  // @formatter:off
  case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
  case class PublicChannel(ann: ChannelAnnouncement, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate]) {
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

  case class MultiPartParams(minPartAmount: MilliSatoshi, maxParts: Int)

  case class RouteParams(maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta, ratios: Option[WeightRatios], mpp: MultiPartParams) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = {
      // The payment fee must satisfy either the flat fee or the percentage fee, not necessarily both.
      maxFeeBase.max(amount * maxFeePct)
    }
  }

  case class Ignore(nodes: Set[PublicKey], channels: Set[ChannelDesc]) {
    // @formatter:off
    def +(ignoreNode: PublicKey): Ignore = copy(nodes = nodes + ignoreNode)
    def ++(ignoreNodes: Set[PublicKey]): Ignore = copy(nodes = nodes ++ ignoreNodes)
    def +(ignoreChannel: ChannelDesc): Ignore = copy(channels = channels + ignoreChannel)
    def emptyNodes(): Ignore = copy(nodes = Set.empty)
    def emptyChannels(): Ignore = copy(channels = Set.empty)
    // @formatter:on
  }

  object Ignore {
    def empty: Ignore = Ignore(Set.empty, Set.empty)
  }

  case class RouteRequest(source: PublicKey,
                          target: PublicKey,
                          amount: MilliSatoshi,
                          maxFee: MilliSatoshi,
                          assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                          ignore: Ignore = Ignore.empty,
                          routeParams: RouteParams,
                          pendingPayments: Seq[Route] = Nil)

  case class FinalizeRoute(amount: MilliSatoshi,
                           hops: Seq[PublicKey],
                           assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                           paymentContext: Option[PaymentContext] = None)

  /**
    * Useful for having appropriate logging context at hand when finding routes
    */
  case class PaymentContext(id: UUID, parentId: UUID, paymentHash: ByteVector32)

  case class Route(amount: MilliSatoshi, hops: Seq[ChannelHop]) {
    require(hops.nonEmpty, "route cannot be empty")

    val length = hops.length
    lazy val fee: MilliSatoshi = {
      val amountToSend = hops.drop(1).reverse.foldLeft(amount) { case (amount1, hop) => amount1 + hop.fee(amount1) }
      amountToSend - amount
    }

    /** This method retrieves the channel update that we used when we built the route. */
    def getChannelUpdateForNode(nodeId: PublicKey): Option[ChannelUpdate] = hops.find(_.nodeId == nodeId).map(_.lastUpdate)

    def printNodes(): String = hops.map(_.nextNodeId).mkString("->")

    def printChannels(): String = hops.map(_.lastUpdate.shortChannelId).mkString("->")

  }

  case class RouteResponse(routes: Seq[Route]) {
    require(routes.nonEmpty, "routes cannot be empty")
  }

  // @formatter:off
  sealed trait GossipDecision { def ann: AnnouncementMessage }
  object GossipDecision {
    case class Accepted(ann: AnnouncementMessage) extends GossipDecision

    sealed trait Rejected extends GossipDecision
    case class Duplicate(ann: AnnouncementMessage) extends Rejected
    case class InvalidSignature(ann: AnnouncementMessage) extends Rejected
    case class NoKnownChannel(ann: NodeAnnouncement) extends Rejected
    case class ValidationFailure(ann: ChannelAnnouncement) extends Rejected
    case class InvalidAnnouncement(ann: ChannelAnnouncement) extends Rejected
    case class ChannelPruned(ann: ChannelAnnouncement) extends Rejected
    case class ChannelClosing(ann: ChannelAnnouncement) extends Rejected
    case class ChannelClosed(ann: ChannelAnnouncement) extends Rejected
    case class Stale(ann: ChannelUpdate) extends Rejected
    case class NoRelatedChannel(ann: ChannelUpdate) extends Rejected
    case class RelatedChannelPruned(ann: ChannelUpdate) extends Rejected
  }
  // @formatter:on

  case class ShortChannelIdAndFlag(shortChannelId: ShortChannelId, flag: Long)

  case class Data(channels: SortedMap[ShortChannelId, PublicChannel], graph: DirectedGraph)

  // @formatter:off
  sealed trait State
  case object NORMAL extends State

  case object TickBroadcast
  case object TickPruneStaleChannels
  case object TickComputeNetworkStats
  // @formatter:on

  def getDesc(u: ChannelUpdate, announcement: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, announcement.nodeId1, announcement.nodeId2) else ChannelDesc(u.shortChannelId, announcement.nodeId2, announcement.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey): Boolean = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[PublicChannel]): Boolean = channels.exists(c => isRelatedTo(c.ann, nodeId))
}