package io.glutenproject.integration.tpc

import java.sql.Date
import org.apache.spark.SparkConf
import org.apache.spark.sql.TypeUtils
import org.apache.spark.sql.types.{DateType, DecimalType, DoubleType, IntegerType, LongType, StringType}

object Constants {

  val VANILLA_CONF: SparkConf = new SparkConf(false)

  val VELOX_CONF: SparkConf = new SparkConf(false)
    .set("spark.gluten.sql.columnar.backend.lib", "velox")
    .set("spark.gluten.sql.columnar.forceShuffledHashJoin", "true")
    .set("spark.sql.parquet.enableVectorizedReader", "true")
    .set("spark.plugins", "io.glutenproject.GlutenPlugin")
    .set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
    .set("spark.sql.optimizer.runtime.bloomFilter.enabled", "true")
    .set("spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold", "0")

  val VELOX_WITH_CELEBORN_CONF: SparkConf = new SparkConf(false)
    .set("spark.gluten.sql.columnar.backend.lib", "velox")
    .set("spark.gluten.sql.columnar.forceShuffledHashJoin", "true")
    .set("spark.sql.parquet.enableVectorizedReader", "true")
    .set("spark.plugins", "io.glutenproject.GlutenPlugin")
    .set("spark.shuffle.manager", "org.apache.spark.shuffle.gluten.celeborn.CelebornShuffleManager")
    .set("spark.celeborn.shuffle.writer", "hash")
    .set("spark.celeborn.push.replicate.enabled", "false")
    .set("spark.shuffle.service.enabled", "false")
    .set("spark.sql.adaptive.localShuffleReader.enabled", "false")
    .set("spark.dynamicAllocation.enabled", "false")
    .set("spark.sql.optimizer.runtime.bloomFilter.enabled", "true")
    .set("spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold", "0")
    .set("spark.celeborn.push.data.timeout", "600s")
    .set("spark.celeborn.push.limit.inFlight.timeout", "1200s")

  @deprecated
  val TYPE_MODIFIER_DATE_AS_DOUBLE: TypeModifier = new TypeModifier(
    TypeUtils.typeAccepts(_, DateType), DoubleType) {
    override def modValue(from: Any): Any = {
      from match {
        case v: Date => v.getTime.asInstanceOf[Double] / 86400.0D / 1000.0D
      }
    }
  }

  @deprecated
  val TYPE_MODIFIER_INTEGER_AS_DOUBLE: TypeModifier = new TypeModifier(
    TypeUtils.typeAccepts(_, IntegerType), DoubleType) {
    override def modValue(from: Any): Any = {
      from match {
        case v: Int => v.asInstanceOf[Double]
      }
    }
  }

  @deprecated
  val TYPE_MODIFIER_LONG_AS_DOUBLE: TypeModifier = new TypeModifier(
    TypeUtils.typeAccepts(_, LongType), DoubleType) {
    override def modValue(from: Any): Any = {
      from match {
        case v: Long => v.asInstanceOf[Double]
      }
    }
  }

  @deprecated
  val TYPE_MODIFIER_DATE_AS_STRING: TypeModifier = new TypeModifier(
    TypeUtils.typeAccepts(_, DateType), StringType) {
    override def modValue(from: Any): Any = {
      from match {
        case v: Date => v.toString
      }
    }
  }

  @deprecated
  val TYPE_MODIFIER_DECIMAL_AS_DOUBLE: TypeModifier = new TypeModifier(
    TypeUtils.decimalAccepts, DoubleType) {
    override def modValue(from: Any): Any = {
      from match {
        case v: java.math.BigDecimal => v.doubleValue()
      }
    }
  }
}
