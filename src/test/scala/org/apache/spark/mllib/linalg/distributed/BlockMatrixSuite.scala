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

package org.apache.spark.mllib.linalg.distributed

import java.{util => ju}

import breeze.linalg.{DenseMatrix => BDM}

import org.apache.spark.{SparkException, SparkFunSuite}
import org.apache.spark.mllib.linalg.{SparseMatrix, DenseMatrix, Matrices, Matrix}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._

class BlockMatrixSuite extends SparkFunSuite with MLlibTestSparkContext {

  val m = 5
  val n = 4
  val rowPerPart = 2
  val colPerPart = 2
  val numPartitions = 3
  var gridBasedMat: BlockMatrix = _

  override def beforeAll() {
    super.beforeAll()
    val blocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 2.0))),
      ((0, 1), new DenseMatrix(2, 2, Array(0.0, 1.0, 0.0, 0.0))),
      ((1, 0), new DenseMatrix(2, 2, Array(3.0, 0.0, 1.0, 1.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(1.0, 2.0, 0.0, 1.0))),
      ((2, 1), new DenseMatrix(1, 2, Array(1.0, 5.0))))

    gridBasedMat = new BlockMatrix(sc.parallelize(blocks, numPartitions), rowPerPart, colPerPart)
  }

  test("size") {
    assert(gridBasedMat.numRows() === m)
    assert(gridBasedMat.numCols() === n)
  }

  test("grid partitioner") {
    val random = new ju.Random()
    // This should generate a 4x4 grid of 1x2 blocks.
    val part0 = GridPartitioner(4, 7, suggestedNumPartitions = 12)
    // scalastyle:off
    val expected0 = Array(
      Array(0, 0, 4, 4,  8,  8, 12),
      Array(1, 1, 5, 5,  9,  9, 13),
      Array(2, 2, 6, 6, 10, 10, 14),
      Array(3, 3, 7, 7, 11, 11, 15))
    // scalastyle:on
    for (i <- 0 until 4; j <- 0 until 7) {
      assert(part0.getPartition((i, j)) === expected0(i)(j))
      assert(part0.getPartition((i, j, random.nextInt())) === expected0(i)(j))
    }

    intercept[IllegalArgumentException] {
      part0.getPartition((-1, 0))
    }

    intercept[IllegalArgumentException] {
      part0.getPartition((4, 0))
    }

    intercept[IllegalArgumentException] {
      part0.getPartition((0, -1))
    }

    intercept[IllegalArgumentException] {
      part0.getPartition((0, 7))
    }

    val part1 = GridPartitioner(2, 2, suggestedNumPartitions = 5)
    val expected1 = Array(
      Array(0, 2),
      Array(1, 3))
    for (i <- 0 until 2; j <- 0 until 2) {
      assert(part1.getPartition((i, j)) === expected1(i)(j))
      assert(part1.getPartition((i, j, random.nextInt())) === expected1(i)(j))
    }

    val part2 = GridPartitioner(2, 2, suggestedNumPartitions = 5)
    assert(part0 !== part2)
    assert(part1 === part2)

    val part3 = new GridPartitioner(2, 3, rowsPerPart = 1, colsPerPart = 2)
    val expected3 = Array(
      Array(0, 0, 2),
      Array(1, 1, 3))
    for (i <- 0 until 2; j <- 0 until 3) {
      assert(part3.getPartition((i, j)) === expected3(i)(j))
      assert(part3.getPartition((i, j, random.nextInt())) === expected3(i)(j))
    }

    val part4 = GridPartitioner(2, 3, rowsPerPart = 1, colsPerPart = 2)
    assert(part3 === part4)

    intercept[IllegalArgumentException] {
      new GridPartitioner(2, 2, rowsPerPart = 0, colsPerPart = 1)
    }

    intercept[IllegalArgumentException] {
      GridPartitioner(2, 2, rowsPerPart = 1, colsPerPart = 0)
    }

    intercept[IllegalArgumentException] {
      GridPartitioner(2, 2, suggestedNumPartitions = 0)
    }
  }

  test("toCoordinateMatrix") {
    val coordMat = gridBasedMat.toCoordinateMatrix()
    assert(coordMat.numRows() === m)
    assert(coordMat.numCols() === n)
    assert(coordMat.toBreeze() === gridBasedMat.toBreeze())
  }

  test("toIndexedRowMatrix") {
    val rowMat = gridBasedMat.toIndexedRowMatrix()
    assert(rowMat.numRows() === m)
    assert(rowMat.numCols() === n)
    assert(rowMat.toBreeze() === gridBasedMat.toBreeze())
  }

  test("toBreeze and toLocalMatrix") {
    val expected = BDM(
      (1.0, 0.0, 0.0, 0.0),
      (0.0, 2.0, 1.0, 0.0),
      (3.0, 1.0, 1.0, 0.0),
      (0.0, 1.0, 2.0, 1.0),
      (0.0, 0.0, 1.0, 5.0))

    val dense = Matrices.fromBreeze(expected).asInstanceOf[DenseMatrix]
    assert(gridBasedMat.toLocalMatrix() === dense)
    assert(gridBasedMat.toBreeze() === expected)
  }

  test("add") {
    val blocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 2.0))),
      ((0, 1), new DenseMatrix(2, 2, Array(0.0, 1.0, 0.0, 0.0))),
      ((1, 0), new DenseMatrix(2, 2, Array(3.0, 0.0, 1.0, 1.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(1.0, 2.0, 0.0, 1.0))),
      ((2, 0), new DenseMatrix(1, 2, Array(1.0, 0.0))), // Added block that doesn't exist in A
      ((2, 1), new DenseMatrix(1, 2, Array(1.0, 5.0))))
    val rdd = sc.parallelize(blocks, numPartitions)
    val B = new BlockMatrix(rdd, rowPerPart, colPerPart)

    val expected = BDM(
      (2.0, 0.0, 0.0, 0.0),
      (0.0, 4.0, 2.0, 0.0),
      (6.0, 2.0, 2.0, 0.0),
      (0.0, 2.0, 4.0, 2.0),
      (1.0, 0.0, 2.0, 10.0))

    val AplusB = gridBasedMat.add(B)
    assert(AplusB.numRows() === m)
    assert(AplusB.numCols() === B.numCols())
    assert(AplusB.toBreeze() === expected)

    val C = new BlockMatrix(rdd, rowPerPart, colPerPart, m, n + 1) // columns don't match
    intercept[IllegalArgumentException] {
      gridBasedMat.add(C)
    }
    val largerBlocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(4, 4, new Array[Double](16))),
      ((1, 0), new DenseMatrix(1, 4, Array(1.0, 0.0, 1.0, 5.0))))
    val C2 = new BlockMatrix(sc.parallelize(largerBlocks, numPartitions), 4, 4, m, n)
    intercept[SparkException] { // partitioning doesn't match
      gridBasedMat.add(C2)
    }
    // adding BlockMatrices composed of SparseMatrices
    val sparseBlocks = for (i <- 0 until 4) yield ((i / 2, i % 2), SparseMatrix.speye(4))
    val denseBlocks = for (i <- 0 until 4) yield ((i / 2, i % 2), DenseMatrix.eye(4))
    val sparseBM = new BlockMatrix(sc.makeRDD(sparseBlocks, 4), 4, 4, 8, 8)
    val denseBM = new BlockMatrix(sc.makeRDD(denseBlocks, 4), 4, 4, 8, 8)

    assert(sparseBM.add(sparseBM).toBreeze() === sparseBM.add(denseBM).toBreeze())
  }

  test("multiply") {
    // identity matrix
    val blocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 1.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 1.0))))
    val rdd = sc.parallelize(blocks, 2)
    val B = new BlockMatrix(rdd, colPerPart, rowPerPart)
    val expected = BDM(
      (1.0, 0.0, 0.0, 0.0),
      (0.0, 2.0, 1.0, 0.0),
      (3.0, 1.0, 1.0, 0.0),
      (0.0, 1.0, 2.0, 1.0),
      (0.0, 0.0, 1.0, 5.0))

    val AtimesB = gridBasedMat.multiply(B)
    assert(AtimesB.numRows() === m)
    assert(AtimesB.numCols() === n)
    assert(AtimesB.toBreeze() === expected)
    val C = new BlockMatrix(rdd, rowPerPart, colPerPart, m + 1, n) // dimensions don't match
    intercept[IllegalArgumentException] {
      gridBasedMat.multiply(C)
    }
    val largerBlocks = Seq(((0, 0), DenseMatrix.eye(4)))
    val C2 = new BlockMatrix(sc.parallelize(largerBlocks, numPartitions), 4, 4)
    intercept[SparkException] {
      // partitioning doesn't match
      gridBasedMat.multiply(C2)
    }
    val rand = new ju.Random(42)
    val largerAblocks = for (i <- 0 until 20) yield ((i % 5, i / 5), DenseMatrix.rand(6, 4, rand))
    val largerBblocks = for (i <- 0 until 16) yield ((i % 4, i / 4), DenseMatrix.rand(4, 4, rand))

    // Try it with increased number of partitions
    val largeA = new BlockMatrix(sc.parallelize(largerAblocks, 10), 6, 4)
    val largeB = new BlockMatrix(sc.parallelize(largerBblocks, 8), 4, 4)
    val largeC = largeA.multiply(largeB)
    val localC = largeC.toLocalMatrix()
    val result = largeA.toLocalMatrix().multiply(largeB.toLocalMatrix().asInstanceOf[DenseMatrix])
    assert(largeC.numRows() === largeA.numRows())
    assert(largeC.numCols() === largeB.numCols())
    assert(localC ~== result absTol 1e-8)
  }

  test("validate") {
    // No error
    gridBasedMat.validate()
    // Wrong MatrixBlock dimensions
    val blocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 2.0))),
      ((0, 1), new DenseMatrix(2, 2, Array(0.0, 1.0, 0.0, 0.0))),
      ((1, 0), new DenseMatrix(2, 2, Array(3.0, 0.0, 1.0, 1.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(1.0, 2.0, 0.0, 1.0))),
      ((2, 1), new DenseMatrix(1, 2, Array(1.0, 5.0))))
    val rdd = sc.parallelize(blocks, numPartitions)
    val wrongRowPerParts = new BlockMatrix(rdd, rowPerPart + 1, colPerPart)
    val wrongColPerParts = new BlockMatrix(rdd, rowPerPart, colPerPart + 1)
    intercept[SparkException] {
      wrongRowPerParts.validate()
    }
    intercept[SparkException] {
      wrongColPerParts.validate()
    }
    // Wrong BlockMatrix dimensions
    val wrongRowSize = new BlockMatrix(rdd, rowPerPart, colPerPart, 4, 4)
    intercept[AssertionError] {
      wrongRowSize.validate()
    }
    val wrongColSize = new BlockMatrix(rdd, rowPerPart, colPerPart, 5, 2)
    intercept[AssertionError] {
      wrongColSize.validate()
    }
    // Duplicate indices
    val duplicateBlocks: Seq[((Int, Int), Matrix)] = Seq(
      ((0, 0), new DenseMatrix(2, 2, Array(1.0, 0.0, 0.0, 2.0))),
      ((0, 0), new DenseMatrix(2, 2, Array(0.0, 1.0, 0.0, 0.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(3.0, 0.0, 1.0, 1.0))),
      ((1, 1), new DenseMatrix(2, 2, Array(1.0, 2.0, 0.0, 1.0))),
      ((2, 1), new DenseMatrix(1, 2, Array(1.0, 5.0))))
    val dupMatrix = new BlockMatrix(sc.parallelize(duplicateBlocks, numPartitions), 2, 2)
    intercept[SparkException] {
      dupMatrix.validate()
    }
  }

  test("transpose") {
    val expected = BDM(
      (1.0, 0.0, 3.0, 0.0, 0.0),
      (0.0, 2.0, 1.0, 1.0, 0.0),
      (0.0, 1.0, 1.0, 2.0, 1.0),
      (0.0, 0.0, 0.0, 1.0, 5.0))

    val AT = gridBasedMat.transpose
    assert(AT.numRows() === gridBasedMat.numCols())
    assert(AT.numCols() === gridBasedMat.numRows())
    assert(AT.toBreeze() === expected)

    // make sure it works when matrices are cached as well
    gridBasedMat.cache()
    val AT2 = gridBasedMat.transpose
    AT2.cache()
    assert(AT2.toBreeze() === AT.toBreeze())
    val A = AT2.transpose
    assert(A.toBreeze() === gridBasedMat.toBreeze())
  }
}
