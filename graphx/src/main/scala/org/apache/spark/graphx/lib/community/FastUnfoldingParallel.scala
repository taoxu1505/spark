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

import org.apache.spark.graphx.{EdgeTriplet, VertexId, Graph}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.conf.Configuration
import org.apache.log4j.{Level, Logger}
import scala.Array
import scala.collection.mutable
import scala.util.Random

object FastUnfoldingParallel {
  def main(args: Array[String]) {
    if (args.size < 4) {
      println("ERROR INPUT!")
      return
    }

    println("FastUnfolding begins...")

    val mode = args(0)  // "local" or yarn-standalone
    if(mode.startsWith("local"))
      Logger.getRootLogger.setLevel(Level.OFF)
    val input = args(1) // input file of edge information
    val partitionNum = args(2).toInt  // partition number
    val output = args(3)  // output file path
    val maxProcessTimes = args(4).toInt
    val minChange = args(5).toDouble
    val maxIters = args(6).toInt

    val fs = FileSystem.get(new Configuration())
    if (fs.exists(new Path(args(3)))) fs.delete(new Path(args(3)), true)

    val sc = new SparkContext(mode, "FastUnfolding")

    process(input, partitionNum, sc, maxProcessTimes, minChange, maxIters)

    outputCommunity(output)

    println("FastUnfolding ends...")
  }

  var improvement = false
  var communityResult: RDD[(Long, Long)] = null
  var graphEdges: RDD[(Long, Long)] = null
  val rand: Random = new Random()

  /**
   * Load edges from file.
   */
  def loadEdgeRdd(edgeFile: String, partitionNum: Int, sc: SparkContext): RDD[(Long, Long)] = {
    val edgeRdd = sc.textFile(edgeFile, partitionNum).flatMap {
      case (line) =>
        val arr = ArrayBuffer[(Long, Long)]()
        val regex = ","
        val ss = line.split(regex)
        if (ss.size >= 2) {
          val src = ss(0).toLong
          val dst = ss(1).toLong
          if (src < dst)
            arr += ((src, dst))
          else
            arr += ((dst, src))
        }
        arr
    }

    edgeRdd
  }

  /**
   * Calculate the self loop numbers for every vertex in graph.
   */
  def generateSelfLoopRdd[VD: ClassTag, ED: ClassTag](graph: Graph[VD, ED]): RDD[(Long, Long)] = {
    val firstPart = graph.edges
      .map(e => if (e.srcId == e.dstId) (e.srcId, 1L) else (e.srcId, 0L))

    val secondPart = graph.edges
      .map(e => if (e.srcId == e.dstId) (e.srcId, 0L) else (e.dstId, 0L))

    (firstPart ++ secondPart).reduceByKey(_ + _)
  }

  /**
   * Generate a new graph with VertexData
   * @param graph original graph
   * @tparam VD
   * @tparam ED
   * @return
   */
  def generateInitGraph[VD: ClassTag, ED: ClassTag](graph: Graph[VD, ED]): Graph[VertexData, ED] = {

    val selfLoopRdd = generateSelfLoopRdd(graph)

    val newGraph = graph.mapVertices[VertexData]((vid, vd) => new VertexData(vid.toLong))
      .joinVertices(graph.degrees){
      case (vid, vertexData, degree) => vertexData.setDegreeAndCommWeight(degree)
    }.joinVertices(selfLoopRdd){
      case (vid, vertexData, selfLoop) => vertexData.setSelfLoop(selfLoop)
    }

    newGraph
  }

  /**
   * Calculate the modularity gain, and return the best community choice.
   * @param array node's neighbors
   * @param totalDegree total degree in the graph
   * @return
   */
  def getBestCommunity(array: Array[VertexData], totalDegree: Double): Long = {
    if (null == array || 0 == array.size) {
      return 0L
    }

    val degree = array(0).degree
    val oriCommunity = array(0).neighCommunity

    if (rand.nextDouble() > 0.2)
      return oriCommunity


    val insideWeightMap = new mutable.HashMap[Long, Long]
    val outsideWeightMap = new mutable.HashMap[Long, Long]
    for(i <- 0 until array.size) {
      val vertexData = array(i)
      val community = vertexData.community
      val preValue = insideWeightMap.getOrElse(community, 0L)
      // TODO 这里的计算方式不一样，paper里面的表述，和源码里的描述不一样
      insideWeightMap.put(community, preValue + 1L)
      outsideWeightMap.put(community, vertexData.communityWeight)
    }

    if (outsideWeightMap.contains(oriCommunity)) {
      val preValue = outsideWeightMap.getOrElse(oriCommunity,0L)
      outsideWeightMap.put(oriCommunity, preValue - degree)
    }

    assert(insideWeightMap.size == outsideWeightMap.size)

    val iter = insideWeightMap.keysIterator
    var bestCommunity = oriCommunity
    var bestGain = 0.0
    while(iter.hasNext) {
      val key = iter.next()
      val insideWeight = insideWeightMap.get(key).getOrElse(0L).toDouble
      val outsideWeight = outsideWeightMap.get(key).getOrElse(0L).toDouble
      val gain = insideWeight - degree * outsideWeight / totalDegree
      if (gain > bestGain) {
        bestGain = gain
        bestCommunity = key
      }
    }

    bestCommunity
  }

  /**
   * Calculate modularity of current community assignment.
   * @param totalDegree total degree of the graph
   * @return
   */
  def calcModularity(totalDegree: Double): Double = {
    //    inRdd.join(totRdd)
    //      .filter(e => e._2._2 > 0)
    //      .map{ e =>
    //      val inValue = e._2._1.toDouble
    //      val totValue = e._2._2.toDouble
    //      inValue / totalDegree - Math.pow(totValue / totalDegree, 2)
    //    }.reduce(_ + _)
    0.0
  }

  def edgeMapFunc[ED: ClassTag](et: EdgeTriplet[VertexData, ED]): Iterator[(VertexId, Array[VertexData])] = {
    if (et.srcId != et.dstId) {
      val srcDegree = et.srcAttr.degree
      val srcComm = et.srcAttr.community
      val srcCommWeight = et.srcAttr.communityWeight
      val dstVertexData = et.dstAttr.setNeighbor(srcDegree, srcComm, srcCommWeight)

      val dstDegree = et.dstAttr.degree
      val dstComm = et.dstAttr.community
      val dstCommWeight = et.dstAttr.communityWeight
      val srcVertexData = et.srcAttr.setNeighbor(dstDegree, dstComm, dstCommWeight)

      Iterator((et.srcId, Array(dstVertexData)), (et.dstId, Array(srcVertexData)))
    } else {
      Iterator.empty
    }
  }

  /**
   * Try to reassign each node to its neighbor community in parallel method.
   * @param graph the original graph
   * @param sc current Spark context
   * @param maxIters maximum times for total iterations
   * @param minChange minimum change, iterations stops if change less than this value
   * @tparam VD
   * @tparam ED
   * @return
   */
  def reCommunityParallel[VD: ClassTag, ED: ClassTag](
      graph: Graph[VD, ED],
      sc: SparkContext,
      maxIters: Int = Int.MaxValue,
      minChange: Double = 0.01): RDD[(Long,Long)] = {

    println("reCommunityParallel...")
    var iters = 0
    val totalDegree = graph.degrees.map(_._2).sum()

    var newGraph = generateInitGraph(graph).cache()
    var curModularity = calcModularity(totalDegree)
    var newModularity = curModularity
    var currentCommunity: RDD[(Long, Long)] = null

    do {
      curModularity = newModularity

      val vertexRdd = newGraph.mapReduceTriplets[Array[VertexData]](edgeMapFunc, _ ++ _)

      val idCommunity = vertexRdd.map{
        case (vid, vdArray) => (vid, getBestCommunity(vdArray, totalDegree))
      }.cache()

      println("---iters: " + iters + "idcommunity count" + idCommunity.count())

      val commWeightTmp = idCommunity.join(newGraph.degrees).map{
        case (vid, (community, degree)) => (community, degree.toLong)
      }

      val commWeight = commWeightTmp.reduceByKey(_ + _)

      val reverseIdCommunity = idCommunity.map(e => (e._2, e._1))
      val updateMessage = reverseIdCommunity.leftOuterJoin(commWeight).map{
        case (community, (vid, weight)) => (vid, (community, weight.getOrElse(0L)))
      }

      val preGraph = newGraph
      newGraph = newGraph.joinVertices(updateMessage){
        case (vid, vertexData, (community, weight))
        =>
          val newVertexData = new VertexData(vertexData)
          newVertexData.setCommAndCommWeight(community, weight)
      }.cache()

      preGraph.unpersistVertices()
      preGraph.edges.unpersist()

      newGraph.vertices.count()
      newGraph.edges.count()

      if (null != currentCommunity) {
        currentCommunity.unpersist()
      }
      currentCommunity = idCommunity.map(e => (e._1.toLong, e._2)).cache()
      println("---iters: " + iters + "currentCommunity count" + currentCommunity.count())
      iters += 1

      //    } while((newModularity - curModularity) > minChange && iters < maxIters)
    } while(iters < maxIters)

    newGraph.unpersistVertices()
    newGraph.edges.unpersist()

    val current = currentCommunity
    if (current.filter(e => e._1 != e._2).count() > 0) {
      improvement = true
      updateCommunity(current)
    } else {
      improvement = false
    }

    reGraphEdges(graph, current)
  }

  /**
   * Update each node's community information.
   */
  def updateCommunity(currentResult: RDD[(Long, Long)]) {
    println("updateCommunity...")
    if (null == currentResult) {
      communityResult = null
    } else if (null == communityResult) {
      communityResult = currentResult.cache()
      communityResult.count()
    } else {
      val preRdd = communityResult
      communityResult = communityResult.map(e => (e._2, e._1))
        .join(currentResult)
        .map{
        case (oldCommunity, (vid, curCommunity)) => (vid, curCommunity)
      }.cache()
      communityResult.count()
      preRdd.unpersist()
    }
  }

  /**
   * Output the community assignment into file.
   * @param file output file
   */
  def outputCommunity(file: String) {
    if (null == communityResult) {
      println("Community Rdd is empty.")
      return
    }

    communityResult.map{
      e => e._1 + "," + e._2
    }.saveAsTextFile(file)
  }



  /**
   * Generate a new edge rdd, according to graph.
   * @param graph the original graph
   * @tparam VD
   * @tparam ED
   * @return
   */
  def reGraphEdges[VD: ClassTag, ED: ClassTag](graph: Graph[VD,ED], result: RDD[(Long, Long)]): RDD[(Long, Long)] = {

    println("reGraphEdges... " + graph.vertices.count())
    val edgeRdd = graph.edges.flatMap{
      case(e) =>
        val arr = ArrayBuffer[(Long, Long)]()
        arr += ((e.srcId, e.dstId))
        arr
    }

    if (null != result) {
      val newEdgeRdd = edgeRdd.leftOuterJoin(result)
        .map(e => (e._2._1, e._2._2.getOrElse(0L)))
        .leftOuterJoin(result)
        .map(e => (e._2._1, e._2._2.getOrElse(0L)))

      newEdgeRdd
    }else{
      edgeRdd
    }
  }

  /**
   * Calculation of current modularity
   */
  def calcCurrentModularity(): Double = {
    if (null == communityResult || null == graphEdges)
      return 0.0

    println("calcCurrentModularity... communityResult " + communityResult.count())

    val reverseCommunityRdd = communityResult.map(e => (e._2, e._1)).cache()

    println("calcCurrentModularity... reverseCommunityRdd " + reverseCommunityRdd.count())

    val commEdgeRdd = reverseCommunityRdd.join(reverseCommunityRdd).map{
      case(community, (srcId, dstId)) =>
        if (srcId < dstId)
          ((srcId, dstId), community)
        else
          ((dstId, srcId), community)
    }.distinct().cache()

    println("calcCurrentModularity... commEdgeRdd " + commEdgeRdd.count())

    val edgeCountRdd = graphEdges.map(e => (e, 1)).reduceByKey(_ + _).cache()


    println("calcCurrentModularity... edgeCountRdd " + edgeCountRdd.count())

    val commEdgeWeightRdd = commEdgeRdd.join(edgeCountRdd).map{
      case (edge, (community, count)) => (edge, count)
    }.cache()

    val commEdgeWeightRddCount = commEdgeWeightRdd.count()
    println("calcCurrentModularity... commEdgeWeightRdd " + commEdgeWeightRddCount)

    edgeCountRdd.unpersist()
    commEdgeRdd.unpersist()

    if (commEdgeWeightRddCount == 0)
      return 0.0


    val graph = Graph.fromEdgeTuples(graphEdges, 1L)
    val degreeRdd = graph.degrees.map(e => (e._1.toLong, e._2.toLong))
    val totalDegree = graph.degrees.map(e => e._2).sum()

    val edgeWeightRdd = graphEdges.distinct()
      .join(degreeRdd)
      .map{
      case (srcId, (dstId, srcDegree)) => (dstId, (srcId, srcDegree))
    }
      .join(degreeRdd)
      .map{
      case (dstId, ((srcId, srcDegree), dstDegree)) =>
        if (dstId < srcId)
          ((dstId, srcId), srcDegree * dstDegree)
        else
          ((srcId, dstId), srcDegree * dstDegree)
    }

    val result = commEdgeWeightRdd.join(edgeWeightRdd)
      .map{
      case (edge, (weight, degree)) => weight.toDouble - degree.toDouble / totalDegree
    }
      .reduce(_ + _)

    println("calcCurrentModularity... result " + result + "\ttotaldegree " + totalDegree)

    commEdgeWeightRdd.unpersist()

    result / totalDegree
  }

  /**
   * Construct graph from input edge file, and finish the community assignment task.
   * @param edgeFile source file of edge information
   * @param partitionNum partition number
   * @param sc current Spark context
   * @param maxProcessTimes times for total iterations
   * @param minChange minimum change, iterations stops if change less than this value
   * @param maxIters maximum times for "pass"
   */
  def process(
               edgeFile: String,
               partitionNum: Int,
               sc: SparkContext,
               maxProcessTimes: Int = Integer.MAX_VALUE,
               minChange: Double = 0.001,
               maxIters: Int = Integer.MAX_VALUE) {

    var current = 0
    graphEdges = loadEdgeRdd(edgeFile, partitionNum, sc).cache()
    var edgeRdd = loadEdgeRdd(edgeFile, partitionNum, sc)

    do{
      val newEdgeRdd = edgeRdd.cache()
      val graph = Graph.fromEdgeTuples(newEdgeRdd, 1L).cache()

      edgeRdd = reCommunityParallel(graph, sc, maxIters, minChange)

      val modularity = calcCurrentModularity()
      println("################ times: " + current + "\tmodularity is: " + modularity)

      newEdgeRdd.unpersist()
      graph.unpersistVertices()
      graph.edges.unpersist()
      current += 1
    } while(improvement && current < maxProcessTimes)

    graphEdges.unpersist()
  }
}
