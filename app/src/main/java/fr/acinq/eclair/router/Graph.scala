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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Btc, MilliBtc}
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.ChannelUpdate

import scala.collection.JavaConversions._
import scala.annotation.tailrec
import scala.collection.mutable

object Graph {

  // @formatter:off
  /**
   * The cumulative weight of a set of edges (path in the graph).
   *
   * @param costs   amount to send to the recipient + each edge's fees per hop
   * @param length number of edges in the path
   * @param cltv   sum of each edge's cltv
   */
  case class RichWeight(costs: Vector[MilliSatoshi], length: Int, cltv: CltvExpiryDelta, weight: Double) extends Ordered[RichWeight] {
    override def compare(that: RichWeight): Int = this.weight.compareTo(that.weight)
  }

  case class WeightedNode(key: PublicKey, weight: RichWeight)
  case class WeightedPath(path: Seq[GraphEdge], weight: RichWeight)
  // @formatter:on

  /**
   * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
   * the same weight we distinguish them by their public key.
   * See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
   */
  object NodeComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.compareTo(y.weight)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.compare(x.weight)
  }

  /**
   * @param graph              the graph on which will be performed the search
   * @param sourceNode         the starting node of the path we're looking for (payer)
   * @param targetNode         the destination node of the path (recipient)
   * @param amount             amount to send to the last node
   * @param ignoredEdges       channels that should be avoided
   * @param ignoredVertices    nodes that should be avoided
   * @param currentBlockHeight the height of the chain tip (latest block)
   * @param boundaries         a predicate function that can be used to impose limits on the outcome of the search
   */
  def bestPath(graph: DirectedGraph,
               sourceNode: PublicKey,
               targetNode: PublicKey,
               amount: MilliSatoshi,
               ignoredEdges: Set[ChannelDesc],
               ignoredVertices: Set[PublicKey],
               currentBlockHeight: Long,
               boundaries: RichWeight => Boolean): Option[WeightedPath] = {
    val targetWeight = RichWeight(Vector(amount), 0, CltvExpiryDelta(0), 0)
    val shortestPath = dijkstraShortestPath(graph, sourceNode, sourceNode, targetNode, ignoredEdges, ignoredVertices, targetWeight, boundaries, currentBlockHeight)
    if (shortestPath.isEmpty) None else Some(WeightedPath(shortestPath, pathWeight(sourceNode, shortestPath, amount, currentBlockHeight)))
  }

  /**
   * Finds the shortest path in the graph, uses a modified version of Dijkstra's algorithm that computes the shortest
   * path from the target to the source (this is because we want to calculate the weight of the edges correctly). The
   * graph @param g is optimized for querying the incoming edges given a vertex.
   *
   * @param g                  the graph on which will be performed the search
   * @param sender             node sending the payment (may be different from sourceNode when calculating partial paths)
   * @param sourceNode         the starting node of the path we're looking for
   * @param targetNode         the destination node of the path
   * @param ignoredEdges       channels that should be avoided
   * @param ignoredVertices    nodes that should be avoided
   * @param initialWeight      weight that will be applied to the target node
   * @param boundaries         a predicate function that can be used to impose limits on the outcome of the search
   * @param currentBlockHeight the height of the chain tip (latest block)
   */
  private def dijkstraShortestPath(g: DirectedGraph,
                                   sender: PublicKey,
                                   sourceNode: PublicKey,
                                   targetNode: PublicKey,
                                   ignoredEdges: Set[ChannelDesc],
                                   ignoredVertices: Set[PublicKey],
                                   initialWeight: RichWeight,
                                   boundaries: RichWeight => Boolean,
                                   currentBlockHeight: Long): Seq[GraphEdge] = {
    // the graph does not contain source/destination nodes
    val sourceNotInGraph = !g.containsVertex(sourceNode)
    val targetNotInGraph = !g.containsVertex(targetNode)
    if (sourceNotInGraph || targetNotInGraph) {
      return Seq.empty
    }

    // conservative estimation to avoid over-allocating memory: this is not the actual optimal size for the maps,
    // because in the worst case scenario we will insert all the vertices.
    val initialCapacity = 100

    val bestWeights = new java.util.HashMap[PublicKey, RichWeight](initialCapacity)
    val bestEdges = new java.util.HashMap[PublicKey, GraphEdge](initialCapacity)
    // NB: we want the elements with smallest weight first, hence the `reverse`.
    val toExplore = mutable.PriorityQueue.empty[WeightedNode](NodeComparator.reverse)

    // initialize the queue and cost array with the initial weight
    bestWeights.put(targetNode, initialWeight)
    toExplore.enqueue(WeightedNode(targetNode, initialWeight))

    var targetFound = false
    while (toExplore.nonEmpty && !targetFound) {
      // node with the smallest distance from the target
      val current = toExplore.dequeue() // O(log(n))
      if (current.key != sourceNode) {
        val currentWeight = bestWeights.get(current.key) // NB: there is always an entry for the current in the 'bestWeights' map
        // build the neighbors with optional extra edges
        val neighborEdges = g.getIncomingEdgesOf(current.key)
        neighborEdges.foreach { edge =>
          val neighbor = edge.desc.a
          // NB: this contains the amount (including fees) that will need to be sent to `neighbor`, but the amount that
          // will be relayed through that edge is the one in `currentWeight`.
          val neighborWeight = addEdgeWeight(sender, edge, currentWeight, currentBlockHeight)

          val currentCost = currentWeight.costs.head

          val canRelayAmount = currentCost <= edge.capacity && edge.update.htlcMaximumMsat.forall(currentCost <= _) && currentCost >= edge.update.htlcMinimumMsat

          if (canRelayAmount && boundaries(neighborWeight) && !ignoredEdges.contains(edge.desc) && !ignoredVertices.contains(neighbor)) {
            val previousNeighborWeight = bestWeights.getOrDefault(neighbor, RichWeight(Vector(Long.MaxValue.msat), Int.MaxValue, CltvExpiryDelta(Int.MaxValue), Double.MaxValue))
            // if this path between neighbor and the target has a shorter distance than previously known, we select it
            if (neighborWeight.weight < previousNeighborWeight.weight) {
              // update the best edge for this vertex
              bestEdges.put(neighbor, edge)
              // add this updated node to the list for further exploration
              toExplore.enqueue(WeightedNode(neighbor, neighborWeight)) // O(1)
              // update the minimum known distance array
              bestWeights.put(neighbor, neighborWeight)
            }
          }
        }
      } else {
        targetFound = true
      }
    }

    if (targetFound) {
      val edgePath = new mutable.ArrayBuffer[GraphEdge](20)
      var current = bestEdges.get(sourceNode)
      while (null != current) {
        edgePath += current
        current = bestEdges.get(current.desc.b)
      }
      edgePath
    } else {
      Seq.empty
    }
  }

  /**
   * Add the given edge to the path and compute the new weight.
   *
   * @param sender             node sending the payment
   * @param edge               the edge we want to cross
   * @param prev               weight of the rest of the path
   * @param currentBlockHeight the height of the chain tip (latest block).
   */
  private def addEdgeWeight(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: Long): RichWeight = {
    import RoutingHeuristics._

    // Every edge is weighted by funding block height where older blocks add less weight, the window considered is 2 months.
    val ageFactor = normalize(ShortChannelId.blockHeight(edge.desc.shortChannelId), min = currentBlockHeight - BLOCK_TIME_8_YEARS, max = currentBlockHeight)

    // Every edge is weighted by channel capacity, larger channels add less weight
    val capFactor = 1 - normalize(edge.capacity.toLong, CAPACITY_CHANNEL_LOW.toLong, CAPACITY_CHANNEL_HIGH.toLong)

    // Every edge is weighted by its cltv-delta value, normalized
    val cltvFactor = normalize(edge.update.cltvExpiryDelta.toInt, CLTV_LOW, CLTV_HIGH)

    // Every edge is weighted by its routing success score, higher score adds less weight
    val successFactor = 1 - normalize(edge.update.score, SCORE_LOW, SCORE_HIGH)

    val totalCost = if (edge.desc.a == sender) prev.costs else addEdgeFees(edge, prev.costs.head) +: prev.costs
    val totalCltv = if (edge.desc.a == sender) prev.cltv else prev.cltv + edge.update.cltvExpiryDelta

    // Every factor adds 0 - 200 imginary SAT to edge weight
    val factor = (ageFactor + capFactor + cltvFactor + successFactor) * 100000L
    val totalWeight = if (edge.desc.a == sender) prev.weight else prev.weight + totalCost.head.toLong + factor

    RichWeight(totalCost, prev.length + 1, totalCltv, totalWeight)
  }

  /**
   * Calculate the minimum amount that the start node needs to receive to be able to forward @amountWithFees to the end
   * node. To avoid infinite loops caused by zero-fee edges, we use a lower bound fee of 1 msat.
   *
   * @param edge            the edge we want to cross
   * @param amountToForward the value that this edge will have to carry along
   * @return the new amount updated with the necessary fees for this edge
   */
  private def addEdgeFees(edge: GraphEdge, amountToForward: MilliSatoshi): MilliSatoshi = {
    if (edgeHasZeroFee(edge)) amountToForward + nodeFee(baseFee = 1.msat, proportionalFee = 0, amountToForward)
    else amountToForward + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountToForward)
  }

  private def edgeHasZeroFee(edge: GraphEdge): Boolean = {
    edge.update.feeBaseMsat.toLong == 0 && edge.update.feeProportionalMillionths == 0
  }

  /**
   * Calculates the total weighted cost of a path.
   * Note that the first hop from the sender is ignored: we don't pay a routing fee to ourselves.
   *
   * @param sender             node sending the payment
   * @param path               candidate path.
   * @param amount             amount to send to the last node.
   * @param currentBlockHeight the height of the chain tip (latest block).
   */
  def pathWeight(sender: PublicKey, path: Seq[GraphEdge], amount: MilliSatoshi, currentBlockHeight: Long): RichWeight = {
    path.foldRight(RichWeight(Vector(amount), 0, CltvExpiryDelta(0), 0)) { (edge, prev) =>
      addEdgeWeight(sender, edge, prev, currentBlockHeight)
    }
  }

  object RoutingHeuristics {

    val BLOCK_TIME_8_YEARS: Int = 4320 * 12 * 8

    // Low/High bound for channel capacity
    val CAPACITY_CHANNEL_LOW: MilliSatoshi = MilliBtc(10).toMilliSatoshi
    val CAPACITY_CHANNEL_HIGH: MilliSatoshi = Btc(20).toMilliSatoshi

    // Low/High bound for CLTV channel value
    val CLTV_LOW = 9
    val CLTV_HIGH = 2016

    val SCORE_LOW = 1
    val SCORE_HIGH = 1000

    /**
     * Normalize the given value between (0, 1). If the @param value is outside the min/max window we flatten it to something very close to the
     * extremes but always bigger than zero so it's guaranteed to never return zero
     */
    def normalize(value: Double, min: Double, max: Double): Double = {
      if (value <= min) 0D
      else if (value >= max) 1D
      else (value - min) / (max - min)
    }

  }

  object GraphStructure {

    case class DescAndCapacity(desc: ChannelDesc, capacity: MilliSatoshi)

    /**
     * Representation of an edge of the graph
     *
     * @param desc        channel description
     * @param update      channel info
     */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate) {

      lazy val capacity: MilliSatoshi = update.htlcMaximumMsat.get // All updates MUST have htlcMaximumMsat

      def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(update.feeBaseMsat, update.feeProportionalMillionths, amount)

      def toDescAndCapacity: DescAndCapacity = DescAndCapacity(desc, capacity)
    }

    /** A graph data structure that uses an adjacency list, stores the incoming edges of the neighbors */
    case class DirectedGraph(private val vertices: Map[PublicKey, List[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

      def addEdges(edges: Iterable[GraphEdge]): DirectedGraph = edges.foldLeft(this)((acc, edge) => acc.addEdge(edge))

      /**
       * Adds an edge to the graph. If one of the two vertices is not found it will be created.
       *
       * @param edge the edge that is going to be added to the graph
       * @return a new graph containing this edge
       */
      def addEdge(edge: GraphEdge): DirectedGraph = {
        val vertexIn = edge.desc.a
        val vertexOut = edge.desc.b
        // the graph is allowed to have multiple edges between the same vertices but only one per channel
        if (containsEdge(edge.desc)) {
          removeEdge(edge.desc).addEdge(edge) // the recursive call will have the original params
        } else {
          val withVertices = addVertex(vertexIn).addVertex(vertexOut)
          DirectedGraph(withVertices.vertices.updated(vertexOut, edge +: withVertices.vertices(vertexOut)))
        }
      }

      /**
       * Removes the edge corresponding to the given pair channel-desc/channel-update,
       * NB: this operation does NOT remove any vertex
       *
       * @param desc the channel description associated to the edge that will be removed
       * @return a new graph without this edge
       */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(desc.b, vertices(desc.b).filterNot(_.desc == desc)))
          case false => this
        }
      }

      def removeEdges(descList: Iterable[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
      }

      /**
       * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
       */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = {
        vertices.get(desc.b).flatMap { adj =>
          adj.find(e => e.desc.shortChannelId == desc.shortChannelId && e.desc.a == desc.a)
        }
      }

      /**
       * @param keyA the key associated with the starting vertex
       * @param keyB the key associated with the ending vertex
       * @return all the edges going from keyA --> keyB (there might be more than one if there are multiple channels)
       */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyB) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.a == keyA)
        }
      }

      /**
       * @param keyB the key associated with the target vertex
       * @return all edges incoming to that vertex
       */
      def getIncomingEdgesOf(keyB: PublicKey): Seq[GraphEdge] = {
        vertices.getOrElse(keyB, List.empty)
      }

      /**
       * Removes a vertex and all its associated edges (both incoming and outgoing)
       */
      def removeVertex(key: PublicKey): DirectedGraph = {
        DirectedGraph(removeEdges(getIncomingEdgesOf(key).map(_.desc)).vertices - key)
      }

      /**
       * Adds a new vertex to the graph, starting with no edges
       */
      def addVertex(key: PublicKey): DirectedGraph = {
        vertices.get(key) match {
          case None => DirectedGraph(vertices + (key -> List.empty))
          case _ => this
        }
      }

      /**
       * Note this operation will traverse all edges in the graph (expensive)
       *
       * @return a list of the outgoing edges of the given vertex. If the vertex doesn't exists an empty list is returned.
       */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = {
        edgeSet().filter(_.desc.a == key).toSeq
      }

      /**
       * @return the set of all the vertices in this graph
       */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
       * @return an iterator of all the edges in this graph
       */
      def edgeSet(): Iterable[GraphEdge] = vertices.values.flatten

      /**
       * @return true if this graph contain a vertex with this key, false otherwise
       */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
       * @return true if this edge desc is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
       */
      def containsEdge(desc: ChannelDesc): Boolean = {
        vertices.get(desc.b) match {
          case None => false
          case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == desc.shortChannelId && neighbor.desc.a == desc.a)
        }
      }

      def prettyPrint(): String = {
        vertices.foldLeft("") { case (acc, (vertex, adj)) =>
          acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
        }
      }
    }

    object DirectedGraph {

      // @formatter:off
      def apply(): DirectedGraph = new DirectedGraph(Map())
      def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))
      def apply(edge: GraphEdge): DirectedGraph = DirectedGraph().addEdge(edge)
      def apply(edges: Seq[GraphEdge]): DirectedGraph = DirectedGraph().addEdges(edges)
      // @formatter:on

      /**
       * This is the recommended way of initializing the network graph (from a public network DB).
       * We only use public channels at first; private channels will be added one by one as they come online, and removed
       * as they go offline.
       * Private channels may be used to route payments, but most of the time, they will be the first or last hop.
       *
       * @param channels map of all known public channels in the network.
       */
      def makeGraph(channels: Map[ShortChannelId, PublicChannel]): DirectedGraph = {
        // initialize the map with the appropriate size to avoid resizing during the graph initialization
        val mutableMap = new java.util.HashMap[PublicKey, List[GraphEdge]](channels.size + 1)

        // add all the vertices and edges in one go
        channels.values.foreach { channel =>
          channel.update_1_opt.foreach { u1 =>
            val desc1 = Router.getDesc(u1, channel.ann)
            addDescToMap(desc1, u1)
          }
          channel.update_2_opt.foreach { u2 =>
            val desc2 = Router.getDesc(u2, channel.ann)
            addDescToMap(desc2, u2)
          }
        }

        def addDescToMap(desc: ChannelDesc, u: ChannelUpdate): Unit = {
          mutableMap.put(desc.b, GraphEdge(desc, u) +: mutableMap.getOrDefault(desc.b, List.empty[GraphEdge]))
          mutableMap.get(desc.a) match {
            case null => mutableMap.put(desc.a, List.empty[GraphEdge])
            case _ =>
          }
        }

        DirectedGraph(mutableMap.toMap)
      }
    }
  }
}
