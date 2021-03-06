package com.clarify.buckets.v2

import java.util

import com.clarify.Helpers
import com.clarify.memory.MemoryDiagnostics
import com.clarify.retry.Retry
import org.apache.spark.sql.functions.{col, hash, lit, pmod}
import org.apache.spark.sql.{AnalysisException, DataFrame, SQLContext}
import org.apache.spark.{SparkContext, SparkException}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process._

object OptimizedBucketWriter {

  val _LOGGER: Logger = LoggerFactory.getLogger(this.getClass.getName)

  def saveAsBucketWithPartitions(sql_ctx: SQLContext, view: String, numBuckets: Int,
                                 location: String,
                                 bucketColumns: util.ArrayList[String],
                                 sortColumns: util.ArrayList[String],
                                 name: String): Boolean = {
    Helpers.log(s"saveAsBucketWithPartitions v2: free memory before (MB): ${MemoryDiagnostics.getFreeMemoryMB}")
    require(location != null, "location cannot be null")
    require(numBuckets > 0, f"numBuckets $numBuckets should be greater than 0")
    require(bucketColumns.size() > 0, f"There were no bucket columns specified")
    require(sortColumns.size() > 0, f"There were no sort columns specified")

    // if folder exists then skip writing
    if (name != null && __folderWithDataExists(sql_ctx, location, name)) {
      Helpers.log(f"Folder $location already exists with data so skipping saving table")
      return true
    }

    val result = Retry.retry(5) {
      _saveBucketsToFileInternal(sql_ctx = sql_ctx, view = view, numBuckets = numBuckets,
        location = location,
        bucketColumns = bucketColumns,
        sortColumns = sortColumns,
        name = name,
        saveLocalAndCopyToS3 = false)
    }
    Await.result(result, 3 hours)
  }

  private def _saveBucketsToFileInternal(sql_ctx: SQLContext, view: String, numBuckets: Int,
                                         location: String,
                                         bucketColumns: util.ArrayList[String],
                                         sortColumns: util.ArrayList[String],
                                         name: String,
                                         saveLocalAndCopyToS3: Boolean): Boolean = {

    require(bucketColumns.size() > 0, f"There were no bucket columns specified")
    require(sortColumns.size() > 0, f"There were no sort columns specified")
    // To avoid S3 slowdown due to writing too many files, write to local and then copy to s3
    val localLocation = if (saveLocalAndCopyToS3 && location.startsWith("s3:")) f"/tmp/checkpoint/$name" else location
    try {
      if (name != null) {
        sql_ctx.sparkContext.setJobDescription(name)
      }
      Helpers.log(s"_saveBucketsToFileInternal v2: view=$view numBuckets=$numBuckets location=$location"
        + f" bucket_columns(${bucketColumns.size()})=$bucketColumns, sort_columns=$sortColumns")
      val df: DataFrame = sql_ctx.table(view)

      val table_name = s"temp_$view"
      sql_ctx.sql(s"DROP TABLE IF EXISTS default.$table_name")
      val my_df: DataFrame = addBucketColumnToDataFrame(df = df, view = view,
        numBuckets = numBuckets, bucketColumns = bucketColumns,
        sortColumns = sortColumns)

      val bucketColumnsSeq: Seq[String] = Helpers.getSeqString(bucketColumns).drop(1)
      val sortColumnsSeq: Seq[String] = Helpers.getSeqString(sortColumns).drop(1)

      my_df
        .write
        .format("parquet")
        //.partitionBy("bucket")
        .bucketBy(numBuckets, colName = bucketColumns.get(0), colNames = bucketColumnsSeq: _*)
        .sortBy(colName = sortColumns.get(0), colNames = sortColumnsSeq: _*)
        .option("path", localLocation)
        .saveAsTable(table_name)

      Helpers.log(s"DROP TABLE default.$table_name")
      sql_ctx.sql(s"DROP TABLE default.$table_name")

      if (saveLocalAndCopyToS3 && location.startsWith("s3:")) {
        Helpers.log(f"s3-dist-cp --s3Endpoint=s3.us-west-2.amazonaws.com --src=hdfs://$localLocation --dest=$location")
        val results = Seq("s3-dist-cp",
          "--s3Endpoint=s3.us-west-2.amazonaws.com",
          f"--src=hdfs://$localLocation",
          f"--dest=$location").!!.trim
        Helpers.log(results)
      }
      Helpers.log(s"_saveBucketsToFileInternal: free memory after (MB): ${MemoryDiagnostics.getFreeMemoryMB}")
      if (!location.startsWith("s3:")) {
        // print free space left
        _printFreeSpace(sql_ctx.sparkContext)
      }
      true
    }
    catch {
      case e: SparkException =>
        val cause = e.getCause
        if (cause != null) {
          Helpers.log(s"readAsBucketWithPartitions: Got SparkException (${cause.getClass}): $cause")
          throw cause
        }
        throw e
      case unknown: Throwable =>
        Helpers.log(s"saveAsBucketWithPartitions: Got some other kind of exception: $unknown")
        throw unknown
    }
  }

  def readAsBucketWithPartitions(sql_ctx: SQLContext, view: String, numBuckets: Int, location: String,
                                 bucketColumns: util.ArrayList[String],
                                 sortColumns: util.ArrayList[String]): Boolean = {

    Helpers.log(s"readAsBucketWithPartitions v2: free memory before (MB): ${MemoryDiagnostics.getFreeMemoryMB}")

    val result = Retry.retry(5) {
      readAsBucketWithPartitionsInternal(sql_ctx, view, numBuckets, location, bucketColumns, sortColumns)
    }
    Await.result(result, 3 hours)
  }

  private def readAsBucketWithPartitionsInternal(sql_ctx: SQLContext, view: String, numBuckets: Int, location: String,
                                                 bucketColumns: util.ArrayList[String],
                                                 sortColumns: util.ArrayList[String]) = {
    Helpers.log(s"readAsBucketWithPartitions v2: view=$view numBuckets=$numBuckets location=$location "
      + f"bucket_columns(${bucketColumns.size()})=$bucketColumns, sort_columns=$sortColumns")
    try {
      val temp_view = s"${view}_temp_bucket_reader"
      val raw_table_name = s"${view}_raw_buckets"
      sql_ctx.sql(s"DROP TABLE IF EXISTS default.$raw_table_name")
      val sql: String = getCreateTableCommand(sql_ctx, numBuckets, location,
        bucketColumns, sortColumns, temp_view, raw_table_name)
      Helpers.log(sql)
      sql_ctx.sql(sql)
      sql_ctx.sql(s"DROP VIEW $temp_view") // done with view
      Helpers.log(s"REFRESH TABLE default.$raw_table_name")
      sql_ctx.sql(s"REFRESH TABLE default.$raw_table_name")
      // sql_ctx.sql(s"DESCRIBE EXTENDED $table_name").show(numRows = 1000)
      val result_df = sql_ctx.table(raw_table_name)
      result_df.createOrReplaceTempView(view)
      // sql_ctx.sql(s"SELECT * FROM $view").explain(extended = true)
      Helpers.log(s"readAsBucketWithPartitions: free memory after (MB): ${MemoryDiagnostics.getFreeMemoryMB}")

      true
    }
    catch {
      case e: SparkException =>
        val cause = e.getCause
        if (cause != null) {
          Helpers.log(s"readAsBucketWithPartitions: Got SparkException (${cause.getClass}): $cause")
          throw cause
        }
        throw e
      case e: AnalysisException =>
        // we do this instead of checking if data frame is empty because the latter is expensive
        if (e.message.startsWith(s"cannot resolve '`${bucketColumns.get(0)}`' given input columns") || e.message.startsWith("Unable to infer schema for Parquet. It must be specified manually")) {
          Helpers.log(s"__internalCheckpointBucketWithPartitions: data frame passed in is empty. $e")
          false
        }
        else {
          Helpers.log(s"__internalCheckpointBucketWithPartitions: Got AnalysisException: $e")
          throw e
        }
      case unknown: Throwable =>
        Helpers.log(s"readAsBucketWithPartitions: Got some other kind of exception: $unknown")
        throw unknown
    }
  }

  private def getCreateTableCommand(sql_ctx: SQLContext, numBuckets: Int, location: String,
                                    bucketColumns: util.ArrayList[String],
                                    sortColumns: util.ArrayList[String],
                                    view_for_schema: String,
                                    table_name: String): String = {
    require(bucketColumns.size() > 0, f"There were no bucket columns specified")
    require(sortColumns.size() > 0, f"There were no sort columns specified")
    // get schema from parquet file without loading data from it
    val df = sql_ctx.read.format("parquet")
      .load(location)

    df.createOrReplaceTempView(view_for_schema)
    val columns = _getColumnsSchema(sql_ctx, view_for_schema)
    val bucket_by_text = Helpers.getSeqString(bucketColumns).mkString(",")
    val sort_by_text = Helpers.getSeqString(sortColumns).mkString(",")
    // have to use CREATE TABLE syntax since that supports bucketing
    var text = s"CREATE TABLE $table_name ("
    text += columns.map(column => s"\n${column(0)} ${column(1)}").mkString(",")
    text += ")\n"
    text +=
      s"""
            USING org.apache.spark.sql.parquet
            OPTIONS (
              path "$location"
            )
            CLUSTERED BY ($bucket_by_text) SORTED BY ($sort_by_text) INTO $numBuckets BUCKETS
            """
    text
  }

  private def _getColumnsSchema(sql_ctx: SQLContext, temp_view: String) = {
    val df_schema = sql_ctx.sql(s"DESCRIBE $temp_view")
    _getColumnSchemaFromDataFrame(df_schema)
  }

  private def _getColumnSchemaFromDataFrame(df_schema: DataFrame) = {
    val columns = df_schema.select(col("col_name"), col("data_type"))
      .rdd.map(x => x.toSeq.toArray).collect()
    columns
  }

  def addBucketColumn(sql_ctx: SQLContext, view: String, result_view: String,
                      numBuckets: Int,
                      bucketColumns: util.ArrayList[String],
                      sortColumns: util.ArrayList[String]): Boolean = {
    Helpers.log(f"addBucketColumn v2: Adding bucket column to $view")
    val df: DataFrame = sql_ctx.table(view)

    val result_df: DataFrame = addBucketColumnToDataFrame(df, view, numBuckets, bucketColumns, sortColumns = sortColumns)

    result_df.createOrReplaceTempView(result_view)
    true
  }

  private def addBucketColumnToDataFrame(df: DataFrame,
                                         view: String,
                                         numBuckets: Int,
                                         bucketColumns: util.ArrayList[String],
                                         sortColumns: util.ArrayList[String]
                                        ): DataFrame = {
    require(bucketColumns.size() > 0, f"There were no bucket columns specified")
    var result_df: DataFrame = df
    val bucketColumnsSeq: Seq[String] = Helpers.getSeqString(bucketColumns)
    val bucketColumnsTypeSeq = bucketColumnsSeq.map(x => col(x))
    val sortColumnsSeq: Seq[String] = Helpers.getSeqString(sortColumns).drop(1)

    Helpers.log(s"Adding bucket column to $view")
    result_df = df
      .withColumn("bucket",
        pmod(
          hash(
            bucketColumnsTypeSeq: _*
          ),
          lit(numBuckets)
        )
      )
      .repartition(numBuckets, col("bucket"))
      .sortWithinPartitions(sortColumns.get(0), sortColumnsSeq: _*)

    result_df
  }

  def __internalCheckpointBucketWithPartitions(sql_ctx: SQLContext, view: String, numBuckets: Int,
                                               location: String,
                                               bucketColumns: util.ArrayList[String],
                                               sortColumns: util.ArrayList[String]): Boolean = {
    Helpers.log(s"__internalCheckpointBucketWithPartitions v2: free memory before (MB): ${MemoryDiagnostics.getFreeMemoryMB}")
    require(bucketColumns.size() > 0, f"There were no bucket columns specified")
    require(sortColumns.size() > 0, f"There were no sort columns specified")
    try {
      val postfix: String = "____"

      val table_prefix = f"temp_${view.toLowerCase()}$postfix"
      // find previous checkpoint tables
      val previous_checkpoint_table_names: Seq[String] =
        sql_ctx.tableNames().filter(x => x.startsWith(table_prefix))
          .sorted.reverse

      println("---tables---")
      sql_ctx.tableNames().foreach(println)
      println("-------------")
      println(f"---- previous_checkpoint_table_names: ${previous_checkpoint_table_names.size} ---")
      previous_checkpoint_table_names.foreach(println)
      println("--------------")

      val previous_checkpoint_numbers: Seq[Int] =
        previous_checkpoint_table_names
          .map(x => x.replace(table_prefix, "").toInt)
          .sorted.reverse

      //      previous_checkpoint_numbers.foreach(println)

      val new_checkpoint_number: Int =
        if (previous_checkpoint_numbers.isEmpty) 1 else previous_checkpoint_numbers.head + 1

      val new_table_name = s"$table_prefix$new_checkpoint_number"

      Helpers.log(s"__internalCheckpointBucketWithPartitions: view=$view table=$new_table_name numBuckets=$numBuckets"
        + f" bucket_columns(${bucketColumns.size()})=$bucketColumns, sort_columns=$sortColumns")
      val df: DataFrame = sql_ctx.table(view)

      val my_df: DataFrame = addBucketColumnToDataFrame(df = df, view = view,
        numBuckets = numBuckets, bucketColumns = bucketColumns,
        sortColumns = sortColumns)

      val bucketColumnsSeq: Seq[String] = Helpers.getSeqString(bucketColumns).drop(1)
      val sortColumnsSeq: Seq[String] = Helpers.getSeqString(sortColumns).drop(1)

      my_df
        .write
        .format("parquet")
        //.partitionBy("bucket")
        .bucketBy(numBuckets, colName = bucketColumns.get(0), colNames = bucketColumnsSeq: _*)
        .sortBy(colName = sortColumns.get(0), colNames = sortColumnsSeq: _*)
        .saveAsTable(new_table_name)

      sql_ctx.sql(s"REFRESH TABLE default.$new_table_name")
      // sql_ctx.sql(s"DESCRIBE EXTENDED $new_table_name").show(numRows = 1000)

      // delete all but latest of the previous checkpoints
      if (previous_checkpoint_numbers.nonEmpty) {
        val tables_to_delete: Seq[String] = previous_checkpoint_numbers.drop(1).map(x => f"$table_prefix$x")
        println(f"---- tables to delete: ${tables_to_delete.size} -----")
        tables_to_delete.foreach(println)
        tables_to_delete.foreach(t => {
          println(f"DROP TABLE default.$t")
          sql_ctx.sql(f"DROP TABLE default.$t")
        })
      }
      Helpers.log(s"__internalCheckpointBucketWithPartitions: free memory after (MB): ${MemoryDiagnostics.getFreeMemoryMB}")
      val result_df = sql_ctx.table(new_table_name)
      result_df.createOrReplaceTempView(view)

      //      for (tableName <- tableNames.filter(t => t.startsWith(original_table_name))){
      //        Helpers.Helpers.log(s"DROP TABLE default.$tableName")
      //        sql_ctx.sql(s"DROP TABLE default.$tableName")
      //      }

      true
    }
    catch {
      case e: SparkException =>
        val cause = e.getCause
        Helpers.log(s"__internalCheckpointBucketWithPartitions: Got SparkException: $cause")
        throw cause
      case e: AnalysisException =>
        // we do this instead of checking if data frame is empty because the latter is expensive
        if (e.message.startsWith(s"cannot resolve '`${bucketColumns.get(0)}`' given input columns")) {
          Helpers.log(s"__internalCheckpointBucketWithPartitions: data frame passed in is empty. $e")
          false
        }
        else {
          Helpers.log(s"__internalCheckpointBucketWithPartitions: Got AnalysisException: $e")
          throw e
        }
      case unknown: Throwable =>
        Helpers.log(s"__internalCheckpointBucketWithPartitions: Got some other kind of exception: $unknown")
        throw unknown
    }
  }

  def checkpointBucketWithPartitions(sql_ctx: SQLContext, view: String, numBuckets: Int,
                                     location: String,
                                     bucketColumns: util.ArrayList[String],
                                     sortColumns: util.ArrayList[String],
                                     name: String = null): Boolean = {

    if (name != null) {
      sql_ctx.sparkContext.setJobDescription(name)
    }
    Helpers.log(s"checkpointBucketWithPartitions v2 for $view, name=$name, location=$location")
    // if location is specified then use external tables
    if (location != null && location.toLowerCase().startsWith("s3")) {
      checkpointBucketToDisk(sql_ctx, view, numBuckets, location, bucketColumns, sortColumns, name)
    } else {
      // use Spark managed tables for better performance
      val result = __internalCheckpointBucketWithPartitions(sql_ctx = sql_ctx, view = view,
        numBuckets = numBuckets, location = location,
        bucketColumns = bucketColumns, sortColumns = sortColumns)
      // print free space left
      _printFreeSpace(sql_ctx.sparkContext)
      result
    }
  }

  def checkpointBucketToDisk(sql_ctx: SQLContext, view: String, numBuckets: Int,
                             location: String,
                             bucketColumns: util.ArrayList[String],
                             sortColumns: util.ArrayList[String],
                             name: String): Boolean = {
    // append name to create a unique location
    val fullLocation = if (location.endsWith("/")) f"$location$name" else f"$location/$name"
    Helpers.log(s"checkpointBucketToDisk v2 for $view, name=$name, location=$fullLocation")
    // if folder already exists then just read from it
    if (name != null && __folderWithDataExists(sql_ctx, fullLocation, name)) {
      Helpers.log(f"Folder $fullLocation already exists with data so skipping saving table")
      sql_ctx.sparkContext.setJobDescription(f"$name (already exists so reading)")
      readAsBucketWithPartitions(sql_ctx = sql_ctx, view = view, numBuckets = numBuckets,
        location = fullLocation, bucketColumns = bucketColumns, sortColumns = sortColumns)
      return true
    }
    // save to location
    val success = saveAsBucketWithPartitions(sql_ctx = sql_ctx, view = view, numBuckets = numBuckets,
      location = fullLocation, bucketColumns = bucketColumns, sortColumns = sortColumns,
      name = name)
    if (success) {
      // val localLocation = if (location.startsWith("s3:")) f"/tmp/checkpoint/$name" else location
      // val localLocation = location
      // read from location
      if (name != null && __folderWithDataExists(sql_ctx, fullLocation, name)) {
        sql_ctx.sparkContext.setJobDescription(f"$name (read after save)")
        readAsBucketWithPartitions(sql_ctx = sql_ctx, view = view, numBuckets = numBuckets,
          location = fullLocation, bucketColumns = bucketColumns,
          sortColumns = sortColumns)
      }
      else {
        // add bucket column to avoid errors
        sql_ctx.table(view).withColumn("bucket", lit(0)).createOrReplaceTempView(view)
        true
      }
    }
    else {
      false
    }
  }

  def checkpointWithoutBuckets(sql_ctx: SQLContext, view: String, numBuckets: Int,
                               location: String,
                               bucketColumns: util.ArrayList[String],
                               sortColumns: util.ArrayList[String],
                               name: String
                              ): Boolean = {

    Helpers.log(s"checkpointWithoutBuckets v2 for $view")
    if (!sql_ctx.table(view).isEmpty) {
      val df = sql_ctx.table(view)
      if (!__folderWithDataExists(sql_ctx, location, name)) {
        df.write.parquet(location)
      }
      val result_df = sql_ctx.read.parquet(location)
      result_df.createOrReplaceTempView(view)
      Helpers.log(s"REFRESH TABLE $view")
      sql_ctx.sql(s"REFRESH TABLE $view")
      sql_ctx.sql(s"DESCRIBE EXTENDED $view").show(numRows = 1000)
      true
    }
    else {
      Helpers.log(s"$view was empty so did not bucket it")
      false
    }
  }

  def checkpointBucketWithPartitionsInMemory(sql_ctx: SQLContext, view: String,
                                             numBuckets: Int, location: String,
                                             bucketColumns: util.ArrayList[String],
                                             sortColumns: util.ArrayList[String]): Boolean = {
    val df = sql_ctx.table(view)
    val rdd = df.rdd
    rdd.cache()
    sql_ctx.createDataFrame(rdd, df.schema).createOrReplaceTempView(view)
    true
  }

  def _printFreeSpace(sparkContext: SparkContext): Boolean = {
    val deployMode: String = sparkContext.getConf.get("spark.submit.deployMode", null)
    if (deployMode != null && deployMode != "client") {
      //noinspection SpellCheckingInspection
      val results = Seq("hdfs", "dfs", "-df", "-h").!!.trim
      Helpers.log(results)
    }
    else {
      Helpers.log("Skipped showing free space since running in client mode")
    }
  }

  def __folderWithDataExists(sql_ctx: SQLContext, location: String, name: String): Boolean = {
    try {
      if (name != null) {
        sql_ctx.sparkContext.setJobDescription(f"$name (check if folder exists)")
      }
      sql_ctx.read.parquet(location).take(1)
      true
    }
    catch {
      case e: SparkException =>
        val cause = e.getCause
        Helpers.log(s"__folderExists: Got SparkException: $cause")
        throw cause
      case e: AnalysisException =>
        // we do this instead of checking if data frame is empty because the latter is expensive
        if (e.message.startsWith(s"Unable to infer schema for Parquet. It must be specified manually.")) {
          Helpers.log(s"__folderExists: data frame passed in is empty $location. $e")
          false
        }
        else if (e.message.startsWith("Path does not exist")) {
          Helpers.log(s"__folderExists: path does not exist $location. $e")
          false
        }
        else {
          Helpers.log(s"__folderExists: Got AnalysisException: $e")
          throw e
        }
      case unknown: Throwable =>
        Helpers.log(s"__folderExists: Got some other kind of exception: $unknown")
        throw unknown
    }
  }

  def s3distCp(src: String, dest: String): Unit = {
    s"s3-dist-cp --src $src --dest $dest".!
  }
}
