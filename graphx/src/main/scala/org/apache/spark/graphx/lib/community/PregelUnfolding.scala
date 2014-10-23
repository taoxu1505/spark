/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.graphx.lib.community

import org.apache.spark.graphx._
import scala.reflect.ClassTag

object PregelUnfolding {

  /**
   * TBD.
   */
  def run[VD: ClassTag, ED: ClassTag](
      graph: Graph[VD, ED],
      maxIter: Int): Graph[Set[VertexId], ED] = {

    graph.cache()
    val numGraphEdges = graph.degrees.map(_._2).fold(0)(_ + _) / 2

    /**
    * Count the number of edges of a sub-graph, given a vertex predication.
    */
    def numberOfEdges(
        predicate: (VertexId, VD) => Boolean): Double = {
      val subgraph = graph.subgraph(vpred = predicate)
      val vert = subgraph.vertices.collect()
      subgraph.degrees.map(_._2).fold(0)(_ + _) / 2.0
    }

    /**
     * Count the number of edges inside a community.
     */
    def countInnerEdges(
        com: Set[VertexId]): Double = {
      numberOfEdges((id, _: VD) => com.contains(id))
    }

    /**
     * Count the number of edges of a sub-graph, which composed of all nodes not in the given
     * community.
     */
    def countOuterEdges(
        com: Set[VertexId]): Double = {
      numberOfEdges((id, _: VD) => ! com.contains(id))
    }

    /**
     * Count the number of edges of a sub-graph, which composed of all nodes in the given two
     * communities.
     */
    def countBipletInnerEdges(
        com1: Set[VertexId],
        com2: Set[VertexId]): Double = {
      numberOfEdges((id, _: VD) => com1.contains(id) || com2.contains(id))
    }

    /**
     * Compute the modularity-gain of merging lhs community with rhs one.
     */
    def modularityGain(
        lhsComm: Set[VertexId],
        rhsComm: Set[VertexId]): Double = {
      val lhsInnerEdges = countInnerEdges(lhsComm)
      val lhsOuterEdges = countOuterEdges(lhsComm)
      val rhsInnerEdges = countInnerEdges(rhsComm)
      val rhsOuterEdges = countOuterEdges(rhsComm)

      val bridgeEdges =
        countBipletInnerEdges(lhsComm, rhsComm) - lhsInnerEdges - rhsInnerEdges

      val edgesConnectedToLHS = numGraphEdges - lhsInnerEdges - lhsOuterEdges
      val edgesConnectedToRHS = numGraphEdges - rhsInnerEdges - rhsOuterEdges

      bridgeEdges / (2 * numGraphEdges)
        - (edgesConnectedToRHS * edgesConnectedToLHS) / (2 * numGraphEdges * numGraphEdges)
    }

    /**
     * Check whether the modularity actually raise after merging two communities.
     */
    def isModularityGained(
        lhsComm: Set[VertexId],
        rhsComm: Set[VertexId]): Boolean = {
      if (modularityGain(lhsComm, rhsComm) > 0) true else false
    }


    var ufWorkGraph = graph.mapVertices((vid, _) => Set[VertexId]())

    var iter = 0

    while (iter < maxIter) {
      iter += 1

      val initialMessage = Set[VertexId]()

      //generate a graph with vertex property as neighbor vertex' indexes
      ufWorkGraph = Pregel(ufWorkGraph, initialMessage, activeDirection = EdgeDirection.Either)(
        (vid, attr, message) => message,

        e => Iterator((e.dstId, Set(e.srcId)),(e.srcId, Set(e.dstId))),

        (neighbor1, neighbor2) => neighbor1 ++ neighbor2
      )

      //generate a graph with four elements as its vertex properties, including
      //1. neighbor vertex's indexes;
      //2. its community's members' indexes;
      //3. the links incidents to its community;
      //4. the links inside its community
      //the 2,3,4 are all for the first pass in fastunfolding


      val firstPassGraph = ufWorkGraph.mapVertices((vid, attr) => (attr, Set(vid), 0, 0))
      val InitialMessage = List()
      firstPassGraph = Pregel(firstPassGraph, InitialMessage, activeDirection = EdgeDirection.Either)(
        (vid, attr, message) => ,

        e => {
          if (e.srcAttr._2.size == 1) {
            Iterator((e.dstId, e.srcAttr._1))
          } else {
            Iterator()
          }
        },

        (neighbor1, neighbor2) => neighbor1 ++ neighbor2
      )
    }
    ufWorkGraph


  }
}

