package com.clarify.sparse_vectors

import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{QueryTest, Row}

class SumTest extends QueryTest with SparkSessionTestWrapper {

  test("sum simple") {
    val v1 = new SparseVector(3, Array(0, 1, 2), Array(1, 2, 3))
    val v3 = new Sum().sparse_vector_sum(v1, 0)
    assert(v3 == 6)
  }
  test("sum") {
    spark.sharedState.cacheManager.clearCache()

    val data = List(
      Row(new SparseVector(3, Array(0, 2), Array(0.1, 0.2))),
      Row(new SparseVector(3, Array(0, 2), Array(0.2, 0.3))),
    )

    val fields = List(
      StructField("v1", VectorType, nullable = false),
    )

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df = spark.createDataFrame(data_rdd, StructType(fields))

    df.createOrReplaceTempView("my_table2")

    df.show()

    val add_function = new Sum().call _

    spark.udf.register("sparse_vector_sum", add_function)

    val out_df = spark.sql(
      "select sparse_vector_sum(v1, 0) as result from my_table2"
    )

    out_df.show()

    checkAnswer(
      out_df.selectExpr("result"),
      Seq(
        Row(0.30000000000000004),
        Row(0.5),
      )
    )
    assert(2 == out_df.count())
  }
}
