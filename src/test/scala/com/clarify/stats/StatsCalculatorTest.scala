package com.clarify.stats

import com.clarify.TestHelpers
import com.clarify.sparse_vectors.SparkSessionTestWrapper
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, QueryTest, Row}

class StatsCalculatorTest extends QueryTest with SparkSessionTestWrapper {

  test("calculate histogram array for column") {
    spark.sharedState.cacheManager.clearCache()

    val my_table = "my_table_stats"

    val data = List(
      Row(1, "foo", 5),
      Row(2, "foo", 5),
      Row(3, "foo", 6),
      Row(4, "foo", 7),
      Row(5, "foo", 5),
      Row(6, "foo", 9),
      Row(7, "bar", 6),
      Row(8, "zoo", 11)
    )
    val fields = List(
      StructField("id", IntegerType, nullable = false),
      StructField("name", StringType, nullable = false),
      StructField("v1", IntegerType, nullable = false)
    )

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    df.createOrReplaceTempView(my_table)

    val normal_columns: Seq[(String, String)] = Seq(("id", "int"), ("name", "string"), ("v1", "int"))
    val columns_to_histogram: Seq[String] = Seq("id", "name", "v1")

    val result: Seq[(String, Int)] =
      StatsCalculator._calculate_histogram_array_for_column("v1", df)

    println(f"result: ${result.size}")
    result.foreach(println)

    val expected: Seq[(String, Int)] = Seq(("5", 3), ("6", 2), ("9", 1), ("7", 1), ("11", 1))
    assert(result == expected)

    //    val histogram_list_all_columns: Seq[(String, Seq[(String, Int)])] = StatsCalculator._create_histogram_array(
    //      columns_to_histogram,
    //      df)
    //
    //    println(f"histogram: ${histogram_list_all_columns.size}")
    //    histogram_list_all_columns.foreach(x => println(x))
    //
    //    val result: DataFrame = StatsCalculator.create_statistics(df, normal_columns,
    //      100, 10, columns_to_histogram, my_table)
    //
    //    result.show(truncate = false)
    TestHelpers.clear_tables(spark_session = spark)
  }
}
