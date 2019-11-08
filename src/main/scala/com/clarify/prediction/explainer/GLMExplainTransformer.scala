package com.clarify.prediction.explainer

import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.functions.expr
import org.apache.spark.sql.types.{DataTypes, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, Row}

class GLMExplainTransformer(override val uid: String) extends Transformer {

  // Transformer Params
  // Defining a Param requires 3 elements:
  //  - Param definition
  //  - Param getter method
  //  - Param setter method
  // (The getter and setter are technically not required, but they are nice standards to follow.)
  def this() = this(Identifiable.randomUID("GLMExplainTransformer"))

  /**
    * Param for input column name.
    */
  final val coefficientView: Param[String] =
    new Param[String](this, "coefficientView", "input coefficient view name")

  final def getCoefficientView: String = $(coefficientView)

  final def setCoefficientView(value: String): GLMExplainTransformer =
    set(coefficientView, value)

  final val linkFunctionType: Param[String] =
    new Param[String](this, "linkFunctionType", "input linkFunction name")

  final def getLinkFunctionType: String = $(linkFunctionType)

  final def setLinkFunctionType(value: String): GLMExplainTransformer =
    set(linkFunctionType, value)

  // (Optional) You can set defaults for Param values if you like.
  setDefault(
    coefficientView -> "coefficient",
    linkFunctionType -> "powerHalfLink"
  )

  private val logLink: String => String = { x: String =>
    s"exp(${x})"
  }
  private val expLink: String => String = { x: String =>
    s"log(${x})"
  }
  private val logitLink: String => String = { x: String =>
    s"(1 / (1 + np.exp(-${x}))"
  }
  private val powerHalfLink: String => String = { x: String =>
    s"pow(${x},2)"
  }
  private val identityLink: String => String = { x: String =>
    s"${x}"
  }

  def buildLinkFunction(linkFunctionType: String): String => String =
    (x: String) => {
      linkFunctionType match {
        case "logLink"      => logLink(x)
        case "expLink"      => expLink(x)
        case "logitLink"    => logitLink(x)
        case "identityLink" => identityLink(x)
        case _              => powerHalfLink(x)
      }
    }

  // Transformer requires 3 methods:
  //  - transform
  //  - transformSchema
  //  - copy

  /**
    * This method implements the main transformation.
    * Its required semantics are fully defined by the method API: take a Dataset or DataFrame,
    * and return a DataFrame.
    *
    * Most Transformers are 1-to-1 row mappings which add one or more new columns and do not
    * remove any columns.  However, this restriction is not required.  This example does a flatMap,
    * so we could either (a) drop other columns or (b) keep other columns, making copies of values
    * in each row as it expands to multiple rows in the flatMap.  We do (a) for simplicity.
    */
  override def transform(dataset: Dataset[_]): DataFrame = {

    val linkFunction = buildLinkFunction($(linkFunctionType))

    val coefficients = dataset.sqlContext
      .table($(coefficientView))
      .select("Feature", "Coefficient")
      .filter("not Feature RLIKE '^.*_OHE___unknown$'")
      .collect()

    val allCoefficients = coefficients
      .map(row => (row.getAs[String](0) -> row.getAs[Double](1)))

    val intercept =
      allCoefficients.find(x => x._1 != "Intercept").get._2

    val featureCoefficients =
      allCoefficients.filter(x => x._1 != "Intercept").toMap

    val df = calculateLinearContrib(
      dataset.toDF(),
      featureCoefficients,
      "linear_contrib"
    )
    val dfWithSigma = calculateSigma(df, featureCoefficients, "linear_contrib")

    val predDf = dfWithSigma.withColumn(
      "pred",
      expr(linkFunction(s"sigma + $intercept"))
    )

    val predPosDF = predDf.withColumn(
      "predPos",
      expr(linkFunction(s"sigmaPos + $intercept"))
    )

    val predNegDF = predPosDF.withColumn(
      "predNeg",
      expr(linkFunction(s"sigmaNeg + $intercept"))
    )

    val contribInterceptDF = predNegDF.withColumn(
      "contrib_intercept",
      expr(linkFunction(s"$intercept"))
    )

    val deficitDF = contribInterceptDF.withColumn(
      "deficit",
      expr("(pred + contrib_intercept) - (predPos + predNeg)")
    )

    val contribPosDF = deficitDF.withColumn(
      "contribPos",
      expr("(predPos - contrib_intercept + deficit) / 2")
    )
    val contribNegsDF = contribPosDF.withColumn(
      "contribNeg",
      expr("(predNeg  - contrib_intercept + deficit) / 2")
    )

    contribNegsDF.show()
    contribNegsDF
  }

  def calculateLinearContrib(
      df: DataFrame,
      featureCoefficients: Map[String, Double],
      prefix: String
  ): DataFrame = {
    val encoder =
      RowEncoder.apply(getSchema(df, featureCoefficients, prefix))
    df.map(mappingLinearContribRows(df.schema)(featureCoefficients))(encoder)
  }

  private val mappingLinearContribRows
      : StructType => Map[String, Double] => Row => Row =
    (schema) =>
      (featureCoefficients) =>
        (row) => {
          val addedCols: List[Double] = featureCoefficients.map {
            case (featureName, coefficient) =>
              row
                .get(schema.fieldIndex(featureName))
                .toString
                .toDouble * coefficient
          }.toList
          Row.merge(row, Row.fromSeq(addedCols))
        }

  private def getSchema(
      df: DataFrame,
      featureCoefficients: Map[String, Double],
      prefix: String
  ): StructType = {
    var schema: StructType = df.schema
    featureCoefficients.foreach {
      case (featureName, _) =>
        schema =
          schema.add(s"${prefix}_${featureName}", DataTypes.DoubleType, false)
    }
    schema
  }

  def calculateSigma(
      df: DataFrame,
      featureCoefficients: Map[String, Double],
      prefix: String
  ): DataFrame = {
    val encoder =
      RowEncoder.apply(getSchema(df, List("sigma", "sigmaPos", "sigmaNeg")))
    df.map(mappingSigmaRows(df.schema, prefix)(featureCoefficients))(encoder)
  }

  private val mappingSigmaRows
      : (StructType, String) => Map[String, Double] => Row => Row =
    (schema, prefix) =>
      (featureCoefficients) =>
        (row) => {
          val calculate: List[Double] = List(
            featureCoefficients.map {
              case (featureName, _) =>
                row
                  .getDouble(schema.fieldIndex(s"${prefix}_${featureName}"))
            }.sum,
            featureCoefficients.map {
              case (featureName, _) =>
                val temp =
                  row.getDouble(schema.fieldIndex(s"${prefix}_${featureName}"))
                if (temp < 0) 0.0 else temp
            }.sum,
            featureCoefficients.map {
              case (featureName, _) =>
                val temp =
                  row.getDouble(schema.fieldIndex(s"${prefix}_${featureName}"))
                if (temp > 0) 0.0 else temp
            }.sum
          )
          Row.merge(row, Row.fromSeq(calculate))
        }

  private def getSchema(
      df: DataFrame,
      columnNames: List[String]
  ): StructType = {
    var schema: StructType = df.schema
    columnNames.foreach {
      case (featureName) =>
        schema = schema.add(s"${featureName}", DataTypes.DoubleType, false)
    }
    schema
  }

  /**
    * Check transform validity and derive the output schema from the input schema.
    *
    * We check validity for interactions between parameters during `transformSchema` and
    * raise an exception if any parameter value is invalid. Parameter value checks which
    * do not depend on other parameters are handled by `Param.validate()`.
    *
    * Typical implementation should first conduct verification on schema change and parameter
    * validity, including complex parameter interaction checks.
    */
  override def transformSchema(schema: StructType): StructType = {
    schema
  }

  /**
    * Creates a copy of this instance.
    * Requirements:
    *  - The copy must have the same UID.
    *  - The copy must have the same Params, with some possibly overwritten by the `extra`
    *    argument.
    *  - This should do a deep copy of any data members which are mutable.  That said,
    *    Transformers should generally be immutable (except for Params), so the `defaultCopy`
    *    method often suffices.
    * @param extra  Param values which will overwrite Params in the copy.
    */
  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)
}