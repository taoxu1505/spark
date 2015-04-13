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

package org.apache.spark.ml.feature

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.ml.UnaryTransformer
import org.apache.spark.ml.param.{IntParam, ParamMap}
import org.apache.spark.mllib.linalg._
import org.apache.spark.sql.types.DataType

/**
 * :: AlphaComponent ::
 * Perform feature expansion in a polynomial space. As said in wikipedia of Polynomial Expansion,
 * which is available at [[http://en.wikipedia.org/wiki/Polynomial_expansion]], "In mathematics, an
 * expansion of a product of sums expresses it as a sum of products by using the fact that
 * multiplication distributes over addition". Take a 2-variable feature vector as an example:
 * `(x, y)`, if we want to expand it with degree 2, then we get `(x, y, x * x, x * y, y * y)`.
 */
@AlphaComponent
class PolynomialMapper extends UnaryTransformer[Vector, Vector, PolynomialMapper] {

  /**
   * The polynomial degree to expand, which should be larger than 1.
   * @group param
   */
  val degree = new IntParam(this, "degree", "the polynomial degree to expand", Some(2))

  /** @group getParam */
  def getDegree: Int = get(degree)

  /** @group setParam */
  def setDegree(value: Int): this.type = set(degree, value)

  override protected def createTransformFunc(paramMap: ParamMap): Vector => Vector = {
    PolynomialMapper.transform(getDegree)
  }

  override protected def outputDataType: DataType = new VectorUDT()
}

object PolynomialMapper {
  /**
   * The number that combines k items from N items without repeat, i.e. the binomial coefficient.
   */
  private def binomialCoefficient(N: Int, k: Int): Int = {
    (N - k + 1 to N).product / (1 to k).product
  }

  /**
   * The number of monomials of a `numVariables` vector after expanding at a specific polynomial
   * degree `degree`.
   */
  private def numMonomials(degree: Int, numVariables: Int): Int = {
    binomialCoefficient(numVariables + degree - 1, degree)
  }

  /**
   * The number of monomials of a `numVariables` vector after expanding from polynomial degree 1 to
   * polynomial degree `degree`.
   */
  private def numExpandedDims(degree: Int, numVariables: Int): Int = {
    binomialCoefficient(numVariables + degree, numVariables) - 1
  }

  /**
   * Given a pre-built array of Double, fill it with expanded monomials to a given polynomial
   * degree. The function works for dense vector.
   * @param values the array of Double, which represents a dense vector.
   * @param prevStart the start offset of elements that filled in the last function call.
   * @param prevLen the length of elements that filled in the last function.
   * @param currDegree the current degree that we want to expand.
   * @param finalDegree the final expected degree that we want to expand.
   * @param nVariables number of variables in the original feature vector.
   */
  @tailrec
  private def fillDenseVector(values: Array[Double], prevStart: Int, prevLen: Int, currDegree: Int,
        finalDegree: Int, nVariables: Int): Unit = {

    if (currDegree > finalDegree) {
      return
    }

    val currExpandedVecFrom = prevStart + prevLen
    val currExpandedVecLen = numMonomials(currDegree, nVariables)

    var leftIndex = 0
    var currIndex = currExpandedVecFrom

    while (leftIndex < nVariables) {
      val numToKeep = numMonomials(currDegree - 1, nVariables - leftIndex)
      val prevVecStartIndex = prevStart + prevLen - numToKeep

      var rightIndex = 0
      while (rightIndex < numToKeep) {
        values(currIndex) =
          values(leftIndex) * values(prevVecStartIndex + rightIndex)
        currIndex += 1
        rightIndex += 1
      }

      leftIndex += 1
    }

    fillDenseVector(values, currExpandedVecFrom, currExpandedVecLen, currDegree + 1, finalDegree,
      nVariables)
  }

  /**
   * Given an array buffer, fill it with expanded monomials to a given polynomial degree. The
   * function works for sparse vector.
   * @param indices the indices buffer, which represents the indices of the expanded sparse vector.
   * @param values the values buffer, which contains the monomials.
   * @param prevStart the start offset that filled in the last function call.
   * @param prevLen the length of elements that filled in the last function call.
   * @param currDegree the current degree that we want to expand.
   * @param finalDegree the final expected degree that we want to expand.
   * @param nVariables number of variables in the original feature vector.
   * @param originalLen the length of the original sparse vector.
   */
  @tailrec
  private def fillSparseVector (
      indices: ArrayBuffer[Int],
      values: ArrayBuffer[Double],
      prevStart: Int,
      prevLen: Int,
      currDegree: Int,
      finalDegree: Int,
      nVariables: Int,
      originalLen: Int): Unit = {

    if (currDegree > finalDegree) {
      return
    }

    val currStart = prevStart + prevLen
    var currLen = 0
    var leftIndex = 0
    while (leftIndex < originalLen) {
      var rightIndex = 0
      while (rightIndex < prevLen) {
        val targetIdx = indexSparseVector(nVariables, currDegree, indices(leftIndex),
          indices(prevStart + rightIndex))
        if (targetIdx == -1) {
          // ignore the invalid indices composition.
        } else {
          indices += targetIdx
          values += values(leftIndex) * values(prevStart + rightIndex)
          currLen += 1
        }
        rightIndex += 1
      }
      leftIndex += 1
    }

    fillSparseVector(indices, values, currStart, currLen, currDegree + 1, finalDegree, nVariables,
      originalLen)
  }

  /**
   * Compute the target index of monomial for sparse vector. The function simulates the process of
   * `x * x's degree-1 polynomial expansion = x's degree polynomial expansion`.
   * @param nVariables number of variables in the original feature vector.
   * @param degree current degree that we want to expand.
   * @param leftIdx the index of the LHS vector, i.e. `x`.
   * @param rightIdx the index of the RHS vector, i.e. `x's degree-1 polynomial expansion`.
   * @return target monomial's index. -1 represents invalid composition of elements.
   */
  private def indexSparseVector(nVariables: Int, degree: Int, leftIdx: Int, rightIdx: Int): Int = {
    val startIdxOfRightVector = numExpandedDims(degree - 2, nVariables)
    val lenOfRightVector = numMonomials(degree - 1, nVariables)
    val startIdxOfTargetVector = numExpandedDims(degree - 1, nVariables)
    val rightResetIdx = rightIdx - startIdxOfRightVector
    val rightIgnoredLen = lenOfRightVector - numMonomials(degree - 1, nVariables - leftIdx)
    val targetResetIdx = rightResetIdx - rightIgnoredLen
    val targetComplementaryLen = (0 until leftIdx)
      .map(x => numMonomials(degree - 1, nVariables - x)).sum
    val targetIdx = if (targetResetIdx >= 0) {
      startIdxOfTargetVector + targetComplementaryLen + targetResetIdx
    } else {
      -1
    }
    targetIdx
  }

  /**
   * Transform a vector of variables into a larger vector which stores the polynomial expansion from
   * degree 1 to degree `degree`.
   */
  private def transform(degree: Int)(feature: Vector): Vector = {
    val expectedDims = numExpandedDims(degree, feature.size)

    feature match {
      case f: DenseVector =>
        val originalLen = f.size

        val values = Array.fill[Double](expectedDims)(0.0)

        for (i <- 0 until f.size) {
          values(i) = f(i)
        }

        fillDenseVector(values, 0, originalLen, 2, degree, originalLen)

        Vectors.dense(values)

      case f: SparseVector =>
        val originalLen = f.indices.size

        val indices = ArrayBuffer.empty[Int]
        val values = ArrayBuffer.empty[Double]

        for (i <- 0 until f.indices.size) {
          indices += f.indices(i)
          values += f.values(i)
        }

        fillSparseVector(indices, values, 0, originalLen, 2, degree, feature.size, originalLen)

        Vectors.sparse(expectedDims, indices.toArray, values.toArray)
    }
  }
}
