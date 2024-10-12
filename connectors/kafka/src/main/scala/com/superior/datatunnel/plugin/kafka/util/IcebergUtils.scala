package com.superior.datatunnel.plugin.kafka.util

import com.superior.datatunnel.common.util.FsUtils
import com.superior.datatunnel.plugin.kafka.DatalakeDatatunnelSinkOption
import org.apache.commons.lang3.StringUtils
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.streaming.Trigger

import java.util.concurrent.TimeUnit

/** https://www.dremio.com/blog/row-level-changes-on-the-lakehouse-copy-on-write-vs-merge-on-read-in-apache-iceberg/
  * https://medium.com/@geekfrosty/copy-on-write-or-merge-on-read-what-when-and-how-64c27061ad56 多数据源简单适配
  * https://www.dremio.com/blog/compaction-in-apache-iceberg-fine-tuning-your-iceberg-tables-data-files/?source=post_page-----a653545de087--------------------------------
  */
object IcebergUtils extends Logging {

  def isIcebergTable(identifier: TableIdentifier): Boolean = {
    val table = SparkSession.active.sessionState.catalog.getTableMetadata(identifier)
    val tableType = table.properties.get("table_type")
    tableType.isDefined && tableType.get.equalsIgnoreCase("iceberg")
  }

  /** delta insert select 操作
    */
  def writeStreamSelectAdapter(
      spark: SparkSession,
      identifier: TableIdentifier,
      checkpointLocation: String,
      triggerProcessingTime: Long,
      sinkOption: DatalakeDatatunnelSinkOption,
      querySql: String
  ): Unit = {
    val catalogTable = spark.sessionState.catalog.getTableMetadata(identifier)

    FsUtils.mkDir(spark, checkpointLocation)

    val streamingInput = spark.sql(querySql)
    val writer = streamingInput.writeStream
      .trigger(Trigger.ProcessingTime(triggerProcessingTime, TimeUnit.SECONDS))
      .format("delta")
      .option("checkpointLocation", checkpointLocation)

    var mergeKeys = sinkOption.getMergeKeys
    val outputMode = sinkOption.getOutputMode
    val partitionColumnNames = sinkOption.getPartitionColumnNames

    writer.options(sinkOption.getProperties)

    if (StringUtils.isNotBlank(partitionColumnNames)) {
      writer.option("fanout-enabled", "true")
    }
    writer.toTable(identifier.toString())

    if (StringUtils.isBlank(mergeKeys)) {
      if (StringUtils.isNotBlank(partitionColumnNames)) {
        writer.partitionBy(StringUtils.split(partitionColumnNames, ","): _*)
      }

      writer
        .outputMode(outputMode.getName)
        .start()
        .awaitTermination()
    } else {
      if (StringUtils.isNotBlank(partitionColumnNames)) {
        mergeKeys = mergeKeys + "," + partitionColumnNames
      }

      val foreachBatchFn = new ForeachBatchFn(mergeKeys, identifier)
      writer
        .foreachBatch(foreachBatchFn)
        .outputMode("update")
        .start()
        .awaitTermination()
    }
  }
}