package com.clarify.prediction.explainer

import com.clarify.sparse_vectors.SparkSessionTestWrapper
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{QueryTest, Row}

class GLMExplainTransformerTest extends QueryTest with SparkSessionTestWrapper {

  test("prediction explainer") {

    spark.sharedState.cacheManager.clearCache()

    val predictionDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/basic/predictions.csv").getPath)

    val coefficientsDF = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(getClass.getResource("/basic/coefficients.csv").getPath)

    coefficientsDF.createOrReplaceTempView("my_coefficients")

    coefficientsDF.show()
    predictionDF.show()

  }

}
