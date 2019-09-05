package com.clarify.buckets

import java.nio.file.Files
import java.util

import com.clarify.sparse_vectors.SparkSessionTestWrapper
import org.apache.spark.sql.functions.{col, hash, lit, pmod}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, QueryTest, Row}

class OptimizedBucketWriterTest extends QueryTest with SparkSessionTestWrapper {

  test("save to buckets") {
    spark.sharedState.cacheManager.clearCache()

    val data = List(
      Row(1, "foo"),
      Row(2, "bar"),
      Row(3, "zoo")
    )
    val fields = List(
      StructField("id", IntegerType, nullable = false),
      StructField("v2", StringType, nullable = false))

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    df.createOrReplaceTempView("my_table")

    val bucket_columns = new util.ArrayList[String]()
    bucket_columns.add("id")

    val location = Files.createTempDirectory("parquet").toFile.toString
    OptimizedBucketWriter.saveAsBucketWithPartitions(sql_ctx = spark.sqlContext,
      view = "my_table", numBuckets = 10, location = location, bucketColumns = bucket_columns)
    println(s"Wrote output to: $location")

    spark.catalog.dropTempView("my_table")

    // now test reading from it
    val result_df: DataFrame = spark.read.parquet(location)
    result_df.show()

    assert(result_df.count() == df.count())
  }

  test("save to buckets multiple") {
    spark.sharedState.cacheManager.clearCache()

    val my_table = "my_table_multiple"

    val data = List(
      Row(1, "foo"),
      Row(2, "bar"),
      Row(3, "zoo")
    )
    val fields = List(
      StructField("id", IntegerType, nullable = false),
      StructField("v2", StringType, nullable = false))

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    df.createOrReplaceTempView(my_table)

    val bucket_columns = new util.ArrayList[String]()
    bucket_columns.add("id")
    bucket_columns.add("v2")

    val location = Files.createTempDirectory("parquet").toFile.toString
    OptimizedBucketWriter.saveAsBucketWithPartitions(sql_ctx = spark.sqlContext,
      view = my_table, numBuckets = 10, location = location, bucketColumns = bucket_columns)
    println(s"Wrote output to: $location")

    val tables = spark.catalog.listTables()
    tables.foreach(t => println(t.name))

    spark.catalog.dropTempView(my_table)
    // now test reading from it
    OptimizedBucketWriter.readAsBucketWithPartitions(sql_ctx = spark.sqlContext,
      view = my_table + "2", numBuckets = 10, location = location, bucketColumns = bucket_columns)
    val result_df = spark.table(my_table + "2")
    result_df.show()

    assert(result_df.count() == df.count())
    spark.sql(s"DESCRIBE EXTENDED ${my_table}2").show(numRows = 1000, truncate = false)
  }

  test("checkpoint") {
    spark.sharedState.cacheManager.clearCache()

    val my_table = "my_table_multiple"

    val data = List(
      Row(1, "foo"),
      Row(2, "bar"),
      Row(3, "zoo")
    )
    val fields = List(
      StructField("id", IntegerType, nullable = false),
      StructField("v2", StringType, nullable = false))

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    df.createOrReplaceTempView(my_table)

    val bucket_columns = new util.ArrayList[String]()
    bucket_columns.add("id")
    bucket_columns.add("v2")

    val location = Files.createTempDirectory("parquet").toFile.toString
    val result = OptimizedBucketWriter.checkpointBucketWithPartitions(sql_ctx = spark.sqlContext,
      view = my_table, numBuckets = 10, location = location, bucketColumns = bucket_columns)
    assert(result)
    println(s"Wrote output to: $location")
    val result_df = spark.table(my_table)

    val tables = spark.catalog.listTables()
    tables.foreach(t => println(t.name))

    // now test reading from it
    result_df.show()

    assert(result_df.count() == df.count())
    spark.sql(s"DESCRIBE EXTENDED $my_table").show(numRows = 1000, truncate = false)
  }

  test("checkpoint empty data frame") {
    spark.sharedState.cacheManager.clearCache()

    val df: DataFrame = spark.emptyDataFrame

    val my_table = "my_table_multiple"
    df.createOrReplaceTempView(my_table)

    val bucket_columns = new util.ArrayList[String]()
    bucket_columns.add("id")
    bucket_columns.add("v2")

    val location = Files.createTempDirectory("parquet").toFile.toString
    println("my view")
    println(df.count())
    println(df.take(1).isEmpty)
    df.show()
    println(spark.sqlContext.table(my_table) count())
    println(spark.sqlContext.table(my_table).take(1).isEmpty)
    val result = OptimizedBucketWriter.checkpointBucketWithPartitions(sql_ctx = spark.sqlContext,
      view = my_table, numBuckets = 10, location = location, bucketColumns = bucket_columns)
    assert(!result)
  }

  test("calculate bucket") {
    spark.sharedState.cacheManager.clearCache()

    val data = List(
      Row(82995L, 28668527357L),
      Row(83021L, 2388667058L),
      Row(83038L, 12444295974L),
      Row(83093L, 605438428L)
    )
    val fields = List(
      StructField("memberuid", LongType, nullable = false),
      StructField("claimuid", LongType, nullable = false))

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    val numBuckets = 8000
    val my_df: DataFrame = df
      .withColumn("buckethash",
        hash(
          col("memberuid")
        )
      )
      .withColumn("bucket",
        pmod(
          hash(
            col("memberuid")
          ),
          lit(numBuckets)
        )
      )

    my_df.show()
  }
  test("calculate bucket two fields") {
    spark.sharedState.cacheManager.clearCache()

    val data = List(
      Row(82995L, 28668527357L),
      Row(83021L, 2388667058L),
      Row(83038L, 12444295974L),
      Row(83093L, 605438428L)
    )
    val fields = List(
      StructField("memberuid", LongType, nullable = false),
      StructField("claimuid", LongType, nullable = false))

    val data_rdd = spark.sparkContext.makeRDD(data)

    val df: DataFrame = spark.createDataFrame(data_rdd, StructType(fields))

    val numBuckets = 8000
    val my_df: DataFrame = df
      .withColumn("buckethash",
        hash(
          col("memberuid"),
          col("claimuid")
        )
      )
      .withColumn("bucket",
        pmod(
          hash(
            col("memberuid"),
            col("claimuid")
          ),
          lit(numBuckets)
        )
      )

    my_df.show()
  }
}
