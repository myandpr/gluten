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
package io.glutenproject.execution.metrics

import io.glutenproject.execution.WholeStageTransformer
import io.glutenproject.memory.alloc.CHNativeMemoryAllocators
import io.glutenproject.metrics.{MetricsUtil, NativeMetrics}
import io.glutenproject.utils.SubstraitPlanPrinterUtil
import io.glutenproject.vectorized.{CHNativeExpressionEvaluator, GeneralInIterator, GeneralOutIterator}

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.catalyst.expressions.Attribute

import java.io.File

import scala.collection.mutable.ListBuffer
import scala.io.Source

object GlutenClickHouseMetricsUTUtils {

  /** Execute substrait plan and return all 'NativeMetrics' */
  def executeSubstraitPlan(
      substraitPlanPath: String,
      basePath: String,
      inBatchIters: java.util.ArrayList[GeneralInIterator],
      outputAttributes: java.util.ArrayList[Attribute]): Seq[NativeMetrics] = {
    val nativeMetricsList = new ListBuffer[NativeMetrics]

    val substraitPlanJsonStr = Source.fromFile(new File(substraitPlanPath), "UTF-8").mkString
    val substraitPlan =
      SubstraitPlanPrinterUtil.jsonToSubstraitPlan(
        substraitPlanJsonStr.replaceAll("basePath", basePath.substring(1)))

    val transKernel = new CHNativeExpressionEvaluator()
    val mockMemoryAllocator = CHNativeMemoryAllocators.contextInstanceForUT()
    val resIter: GeneralOutIterator = transKernel.createKernelWithBatchIterator(
      mockMemoryAllocator.getNativeInstanceId,
      substraitPlan.toByteArray,
      inBatchIters,
      outputAttributes)
    val iter = new Iterator[Any] {
      private var outputRowCount = 0L
      private var outputVectorCount = 0L

      override def hasNext: Boolean = {
        val res = resIter.hasNext
        if (!res) {
          val nativeMetrics = resIter.getMetrics.asInstanceOf[NativeMetrics]
          nativeMetrics.setFinalOutputMetrics(outputRowCount, outputVectorCount)
          nativeMetricsList.append(nativeMetrics)
        }
        res
      }

      override def next(): Any = {
        val cb = resIter.next()
        outputVectorCount += 1
        outputRowCount += cb.numRows()
        cb
      }
    }

    iter.foreach(_.toString)
    resIter.close()
    mockMemoryAllocator.close()

    nativeMetricsList.toSeq
  }

  def getTPCHQueryExecution(spark: SparkSession, queryNum: Int, tpchQueries: String): DataFrame = {
    val sqlNum = "q" + "%02d".format(queryNum)
    val sqlFile = tpchQueries + "/" + sqlNum + ".sql"
    val sqlStr = Source.fromFile(new File(sqlFile), "UTF-8").mkString
    spark.sql(sqlStr)
  }

  def getTPCDSQueryExecution(
      spark: SparkSession,
      queryNum: String,
      tpcdsQueries: String): DataFrame = {
    val sqlFile = tpcdsQueries + "/" + queryNum + ".sql"
    spark.sql(Source.fromFile(new File(sqlFile), "UTF-8").mkString)
  }

  /** Execute metrics updater by metrics json file */
  def executeMetricsUpdater(wholeStageTransformer: WholeStageTransformer, metricsJsonFile: String)(
      customCheck: () => Unit): Unit = {
    val wholestageTransformContext = wholeStageTransformer.doWholestageTransform()

    val wholeStageTransformerUpdaterTree =
      MetricsUtil.treeifyMetricsUpdaters(wholeStageTransformer.child)
    val relMap = wholestageTransformContext.substraitContext.registeredRelMap
    val wholeStageTransformerUpdater = MetricsUtil.updateTransformerMetrics(
      wholeStageTransformerUpdaterTree,
      relMap,
      new java.lang.Long(relMap.size() - 1),
      wholestageTransformContext.substraitContext.registeredJoinParams,
      wholestageTransformContext.substraitContext.registeredAggregationParams
    )

    val nativeMetrics =
      new NativeMetrics(Source.fromFile(new File(metricsJsonFile), "UTF-8").mkString)
    wholeStageTransformerUpdater(nativeMetrics)
    customCheck()
  }
}
