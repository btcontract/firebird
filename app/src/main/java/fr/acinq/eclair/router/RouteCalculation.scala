/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, RoutingHeuristics, WeightRatios}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{ShortChannelId, _}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

object RouteCalculation {
  def handleRouteRequest(d: Data, routerConf: RouterConf, currentBlockHeight: Long, r: RouteRequest): Try[Seq[Route]] = {
    // we convert extra routing info provided in the payment request to fake channel_update
    // it takes precedence over all other channel_updates we know
    val assistedChannels: Map[ShortChannelId, AssistedChannel] = r.assistedRoutes.flatMap(toAssistedChannels(_, r.target, r.amount))
      .filterNot { case (_, ac) => ac.extraHop.nodeId == r.source } // we ignore routing hints for our own channels, we have more accurate information
      .toMap
    val extraEdges = assistedChannels.values.map(ac =>
      GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum), Some(ac.htlcMaximum))
    ).toSet
    findMultiPartRoute(d.graph, r.source, r.target, r.amount, r.maxFee, extraEdges, r.ignore.channels, r.ignore.nodes, r.pendingPayments, r.routeParams, currentBlockHeight)
  }

  private def toFakeUpdate(extraHop: ExtraHop, htlcMaximum: MilliSatoshi): ChannelUpdate = {
    // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
    // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, System.currentTimeMillis.milliseconds.toSeconds, messageFlags = 1,
      channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0.msat, extraHop.feeBase, extraHop.feeProportionalMillionths, Some(htlcMaximum))
  }

  def toAssistedChannels(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey, amount: MilliSatoshi): Map[ShortChannelId, AssistedChannel] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    // The invoice doesn't explicitly specify the channel's htlcMaximumMsat, but we can safely assume that the channel
    // should be able to route the payment, so we'll compute an htlcMaximumMsat accordingly.
    // We could also get the channel capacity from the blockchain (since we have the shortChannelId) but that's more expensive.
    // We also need to make sure the channel isn't excluded by our heuristics.
    val lastChannelCapacity = amount.max(RoutingHeuristics.CAPACITY_CHANNEL_LOW)
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).reverse.foldLeft((lastChannelCapacity, Map.empty[ShortChannelId, AssistedChannel])) {
      case ((amount1, acs), (extraHop: ExtraHop, nextNodeId)) =>
        val nextAmount = amount1 + nodeFee(extraHop.feeBase, extraHop.feeProportionalMillionths, amount1)
        (nextAmount, acs + (extraHop.shortChannelId -> AssistedChannel(extraHop, nextNodeId, nextAmount)))
    }._2
  }

  def toChannelDescs(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Seq[ChannelDesc] = {
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map { case (hop, nextNodeId) => ChannelDesc(hop.shortChannelId, hop.nodeId, nextNodeId) }
  }

  /** https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications */
  val ROUTE_MAX_LENGTH = 20

  /** Max allowed CLTV for a route (one week) */
  val DEFAULT_ROUTE_MAX_CLTV = CltvExpiryDelta(1008)

  /** The default number of routes we'll search for when findRoute is called with randomize = true */
  val DEFAULT_ROUTES_COUNT = 3

  def getDefaultRouteParams(routerConf: RouterConf): RouteParams = RouteParams(
    maxFeeBase = routerConf.searchMaxFeeBase.toMilliSatoshi,
    maxFeePct = routerConf.searchMaxFeePct,
    routeMaxLength = routerConf.firstPassMaxRouteLength,
    routeMaxCltv = routerConf.searchMaxCltv,
    ratios = routerConf.searchHeuristicsEnabled match {
      case false => None
      case true => Some(WeightRatios(
        cltvDeltaFactor = routerConf.searchRatioCltv,
        ageFactor = routerConf.searchRatioChannelAge,
        capacityFactor = routerConf.searchRatioChannelCapacity,
        successScoreFactor = routerConf.searchRatioSuccessScore
      ))
    },
    mpp = MultiPartParams(routerConf.mppMinPartAmount, routerConf.mppMaxParts)
  )

  /**
    * Find a multi-part route in the graph between localNodeId and targetNodeId.
    *
    * @param g               graph of the whole network
    * @param localNodeId     sender node (payer)
    * @param targetNodeId    target node (final recipient)
    * @param amount          the amount that the target node should receive
    * @param maxFee          the maximum fee of a resulting route
    * @param extraEdges      a set of extra edges we want to CONSIDER during the search
    * @param ignoredEdges    a set of extra edges we want to IGNORE during the search
    * @param ignoredVertices a set of extra vertices we want to IGNORE during the search
    * @param pendingHtlcs    a list of htlcs that have already been sent for that multi-part payment (used to avoid finding conflicting HTLCs)
    * @param routeParams     a set of parameters that can restrict the route search
    * @return a set of disjoint routes to the destination @param targetNodeId with the payment amount split between them
    */
  def findMultiPartRoute(g: DirectedGraph,
                         localNodeId: PublicKey,
                         targetNodeId: PublicKey,
                         amount: MilliSatoshi,
                         maxFee: MilliSatoshi,
                         extraEdges: Set[GraphEdge] = Set.empty,
                         ignoredEdges: Set[ChannelDesc] = Set.empty,
                         ignoredVertices: Set[PublicKey] = Set.empty,
                         pendingHtlcs: Seq[Route] = Nil,
                         routeParams: RouteParams,
                         currentBlockHeight: Long): Try[Seq[Route]] = Try {
    val result = findMultiPartRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, extraEdges, ignoredEdges, ignoredVertices, pendingHtlcs, routeParams, currentBlockHeight) match {
      case Right(routes) => Right(routes)
      case Left(ex) => Left(ex)
    }
    result match {
      case Right(routes: Seq[Route]) => routes
      case Left(ex) => return Failure(ex)
    }
  }

  private def findMultiPartRouteInternal(g: DirectedGraph,
                                         localNodeId: PublicKey,
                                         targetNodeId: PublicKey,
                                         amount: MilliSatoshi,
                                         maxFee: MilliSatoshi,
                                         extraEdges: Set[GraphEdge] = Set.empty,
                                         ignoredEdges: Set[ChannelDesc] = Set.empty,
                                         ignoredVertices: Set[PublicKey] = Set.empty,
                                         pendingHtlcs: Seq[Route] = Nil,
                                         routeParams: RouteParams,
                                         currentBlockHeight: Long): Either[RouterException, Seq[Route]] = {
    // We use Yen's k-shortest paths to find many paths for chunks of the total amount.
    val numRoutes = {
      val directChannelsCount = g.getEdgesBetween(localNodeId, targetNodeId).length
      routeParams.mpp.maxParts.max(directChannelsCount) // if we have direct channels to the target, we can use them all
    }
    val routeAmount = routeParams.mpp.minPartAmount.min(amount)
    findRouteInternal(g, localNodeId, targetNodeId, routeAmount, maxFee, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams, currentBlockHeight) match {
      case Right(routes) =>
        // We use these shortest paths to find a set of non-conflicting HTLCs that send the total amount.
        split(amount, mutable.Queue(routes: _*), initializeUsedCapacity(pendingHtlcs), routeParams) match {
          case Right(routes1) if validateMultiPartRoute(amount, maxFee, routes1) => Right(routes1)
          case _ => Left(RouteNotFound)
        }
      case Left(ex) => Left(ex)
    }
  }

  @tailrec
  private def findRouteInternal(g: DirectedGraph,
                                localNodeId: PublicKey,
                                targetNodeId: PublicKey,
                                partialAmount: MilliSatoshi,
                                maxFee: MilliSatoshi,
                                numRoutes: Int,
                                extraEdges: Set[GraphEdge] = Set.empty,
                                ignoredEdges: Set[ChannelDesc] = Set.empty,
                                ignoredVertices: Set[PublicKey] = Set.empty,
                                routeParams: RouteParams,
                                currentBlockHeight: Long): Either[RouterException, Seq[Graph.WeightedPath]] = {
    if (localNodeId == targetNodeId) return Left(CannotRouteToSelf)

    def feeOk(fee: MilliSatoshi): Boolean = fee <= maxFee

    def lengthOk(length: Int): Boolean = length <= routeParams.routeMaxLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.routeMaxCltv

    val boundaries: RichWeight => Boolean = { weight => feeOk(weight.cost - partialAmount) && lengthOk(weight.length) && cltvOk(weight.cltv) }

    val foundRoutes: Seq[Graph.WeightedPath] = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, partialAmount, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries)
    if (foundRoutes.nonEmpty) {
      val (directRoutes, indirectRoutes) = foundRoutes.partition(_.path.length == 1)
      Right(directRoutes ++ indirectRoutes)
    } else if (routeParams.routeMaxLength < ROUTE_MAX_LENGTH) {
      // if not found within the constraints we relax and repeat the search
      val relaxedRouteParams = routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV)
      findRouteInternal(g, localNodeId, targetNodeId, partialAmount, maxFee, numRoutes, extraEdges, ignoredEdges, ignoredVertices, relaxedRouteParams, currentBlockHeight)
    } else {
      Left(RouteNotFound)
    }
  }

  @tailrec
  private def split(amount: MilliSatoshi, paths: mutable.Queue[Graph.WeightedPath], usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi], routeParams: RouteParams, selectedRoutes: Seq[Route] = Nil): Either[RouterException, Seq[Route]] = {
    if (amount == 0.msat) {
      Right(selectedRoutes)
    } else if (paths.isEmpty) {
      Left(RouteNotFound)
    } else {
      val current: Graph.WeightedPath = paths.dequeue()
      val candidate = computeRouteMaxAmount(current.path, usedCapacity)
      if (candidate.amount < routeParams.mpp.minPartAmount.min(amount)) {
        // this route doesn't have enough capacity left: we remove it and continue.
        split(amount, paths, usedCapacity, routeParams, selectedRoutes)
      } else {
        val route = candidate.copy(amount = candidate.amount.min(amount))
        updateUsedCapacity(route, usedCapacity)
        paths.enqueue(current)
        // NB: we re-enqueue the current path, it may still have capacity for a second HTLC.
        split(amount - route.amount, paths, usedCapacity, routeParams, route +: selectedRoutes)
      }
    }
  }

  /** Compute the maximum amount that we can send through the given route. */
  private def computeRouteMaxAmount(route: Seq[GraphEdge], usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi]): Route = {
    val firstHopMaxAmount = route.head.maxHtlcAmount(usedCapacity.getOrElse(route.head.update.shortChannelId, 0.msat))
    val amount = route.drop(1).foldLeft(firstHopMaxAmount) { case (amount1, edge) =>
      // We compute fees going forward instead of backwards. That means we will slightly overestimate the fees of some
      // edges, but we will always stay inside the capacity bounds we computed.
      val amountMinusFees = amount1 - edge.fee(amount1)
      val edgeMaxAmount = edge.maxHtlcAmount(usedCapacity.getOrElse(edge.update.shortChannelId, 0.msat))
      amountMinusFees.min(edgeMaxAmount)
    }
    Route(amount.max(0.msat), route.map(graphEdgeToHop))
  }

  /** Initialize known used capacity based on pending HTLCs. */
  private def initializeUsedCapacity(pendingHtlcs: Seq[Route]): mutable.Map[ShortChannelId, MilliSatoshi] = {
    val usedCapacity = mutable.Map.empty[ShortChannelId, MilliSatoshi]
    // We always skip the first hop: since they are local channels, we already take into account those sent HTLCs in the
    // channel balance (which overrides the channel capacity in route calculation).
    pendingHtlcs.filter(_.hops.length > 1).foreach(route => updateUsedCapacity(route.copy(hops = route.hops.tail), usedCapacity))
    usedCapacity
  }

  /** Update used capacity by taking into account an HTLC sent to the given route. */
  private def updateUsedCapacity(route: Route, usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi]): Unit = {
    route.hops.reverse.foldLeft(route.amount) { case (amount, hop) =>
      val currentMsat = usedCapacity.getOrElse(hop.lastUpdate.shortChannelId, 0.msat)
      usedCapacity(hop.lastUpdate.shortChannelId) = currentMsat + amount
      amount + hop.fee(amount)
    }
  }

  private def validateMultiPartRoute(amount: MilliSatoshi, maxFee: MilliSatoshi, routes: Seq[Route]): Boolean = {
    val amountOk = routes.map(_.amount).sum == amount
    val feeOk = routes.map(_.fee).sum <= maxFee
    amountOk && feeOk
  }
}
