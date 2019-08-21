package com.clarify.sparse_vectors
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector}
import scala.collection.mutable
import org.apache.spark.ml.linalg.Vectors
import scala.util.control.Breaks._
import org.apache.spark.sql.api.java.UDF1

class SparseVectorProduct extends UDF1[SparseVector, Double] {

  override def call(v1: SparseVector): Double = {
    sparse_vector_product(v1)
  }

  def sparse_vector_product(
      v1: SparseVector
  ): Double = {
      var product: Double = 1
    for (i <- 0 until (v1.indices.size)) {
      val index = v1.indices(i)
      product = product * v1.values(i)
    }
    return product
  }
}