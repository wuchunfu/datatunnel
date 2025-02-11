package com.superior.datatunnel.plugin.kafka.reader

import com.superior.datatunnel.api.model.DataTunnelSourceOption
import com.superior.datatunnel.api.{DataSourceType, DataTunnelContext, DataTunnelException, DataTunnelSource}
import com.superior.datatunnel.plugin.doris.DorisDataTunnelSinkOption
import com.superior.datatunnel.plugin.kafka.{
  DatalakeDatatunnelSinkOption,
  KafkaDataTunnelSinkOption,
  KafkaDataTunnelSourceOption
}
import com.superior.datatunnel.plugin.kafka.util.{
  DeltaUtils,
  DorisUtils,
  HudiUtils,
  IcebergUtils,
  PaimonUtils,
  StarrocksUtils
}
import com.superior.datatunnel.plugin.starrocks.StarrocksDataTunnelSinkOption
import org.apache.commons.lang3.StringUtils
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Dataset, Row}

/** huaixin 2021/12/29 2:23 PM
  */
class KafkaDataTunnelSource extends DataTunnelSource with Logging {

  override def read(context: DataTunnelContext): Dataset[Row] = {
    val tmpTable = "tdl_datatunnel_kafka_" + System.currentTimeMillis()
    val sourceOption =
      context.getSourceOption.asInstanceOf[KafkaDataTunnelSourceOption]
    KafkaSupport.createStreamTempTable(tmpTable, sourceOption)

    val format = StringUtils.lowerCase(sourceOption.getFormat)
    if (!("text".equals(format) || "json".equals(format))) {
      throw new IllegalArgumentException("kafka source format 仅支持 text 或 json，当前值: " + format)
    }

    val sinkType = context.getSinkOption.getDataSourceType
    if (DataSourceType.HUDI == sinkType) {
      writeHudi(context, sourceOption, tmpTable)
    } else if (DataSourceType.PAIMON == sinkType) {
      writePaimon(context, sourceOption, tmpTable)
    } else if (DataSourceType.DELTA == sinkType) {
      writeDelta(context, sourceOption, tmpTable)
    } else if (DataSourceType.ICEBERG == sinkType) {
      writeIceberg(context, sourceOption, tmpTable)
    } else if (DataSourceType.LOG == sinkType) {
      writeLog(context, sourceOption, tmpTable)
    } else if (DataSourceType.KAFKA == sinkType) {
      writeKafka(context, sourceOption, tmpTable)
    } else if (DataSourceType.DORIS == sinkType) {
      writeDoris(context, sourceOption, tmpTable)
    } else if (DataSourceType.STARROCKS == sinkType) {
      writeStarrocks(context, sourceOption, tmpTable)
    } else {
      throw new UnsupportedOperationException("kafka 数据不支持同步到 " + sinkType)
    }

    null
  }

  private def writeLog(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    val dataset = context.getSparkSession.sql(querySql)

    val query = dataset.writeStream
      .outputMode("append")
      .format("console")
      .start()
    query.awaitTermination()
  }

  private def writeKafka(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val kafkaSinkOption =
      context.getSinkOption.asInstanceOf[KafkaDataTunnelSinkOption]
    val querySql = buildQuerySqlForKafka(context, sourceOption, tmpTable)
    val dataset = context.getSparkSession.sql(querySql)

    var writer = dataset.writeStream
    writer.options(kafkaSinkOption.getProperties)
    writer.option("key.serializer", classOf[StringSerializer].getName)
    writer.option("value.serializer", classOf[StringSerializer].getName)

    writer = writer
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaSinkOption.getServers)
      .option("topic", kafkaSinkOption.getTopic)

    val checkpointLocation = sourceOption.getCheckpointLocation
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    } else {
      writer.option("checkpointLocation", checkpointLocation)
    }

    writer.start().awaitTermination()
  }

  private def writeHudi(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sparkSession = context.getSparkSession
    val sinkOption =
      context.getSinkOption.asInstanceOf[DatalakeDatatunnelSinkOption]
    val databaseName = sinkOption.getDatabaseName
    val tableName = sinkOption.getTableName
    val identifier = TableIdentifier(tableName, Some(databaseName))
    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    if (!HudiUtils.isHudiTable(identifier)) {
      throw new DataTunnelException(s"${identifier} 不是 hudi 表")
    }
    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    HudiUtils.writeStreamSelectAdapter(
      sparkSession,
      identifier,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def writePaimon(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sparkSession = context.getSparkSession
    val sinkOption =
      context.getSinkOption.asInstanceOf[DatalakeDatatunnelSinkOption]
    val databaseName = sinkOption.getDatabaseName
    val tableName = sinkOption.getTableName
    val identifier = TableIdentifier(tableName, Some(databaseName))
    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    if (!PaimonUtils.isPaimonTable(identifier)) {
      throw new DataTunnelException(
        throw new DataTunnelException(s"${identifier} 不是 paimon 表")
      )
    }

    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    PaimonUtils.writeStreamSelectAdapter(
      sparkSession,
      identifier,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def writeDelta(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sparkSession = context.getSparkSession
    val sinkOption =
      context.getSinkOption.asInstanceOf[DatalakeDatatunnelSinkOption]
    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    val databaseName = sinkOption.getDatabaseName
    val tableName = sinkOption.getTableName
    val identifier = TableIdentifier(tableName, Some(databaseName))
    if (!DeltaUtils.isDeltaTable(identifier)) {
      throw new DataTunnelException(
        throw new DataTunnelException(s"${identifier} 不是 delta 表")
      )
    }

    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    DeltaUtils.writeStreamSelectAdapter(
      sparkSession,
      identifier,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def writeIceberg(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sparkSession = context.getSparkSession
    val sinkOption =
      context.getSinkOption.asInstanceOf[DatalakeDatatunnelSinkOption]

    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    val databaseName = sinkOption.getDatabaseName
    val tableName = sinkOption.getTableName
    val identifier = TableIdentifier(tableName, Some(databaseName))
    if (!IcebergUtils.isIcebergTable(identifier)) {
      throw new DataTunnelException(
        throw new DataTunnelException(s"${identifier} 不是 iceberg 表")
      )
    }

    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    IcebergUtils.writeStreamSelectAdapter(
      sparkSession,
      identifier,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def writeDoris(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sinkOption =
      context.getSinkOption.asInstanceOf[DorisDataTunnelSinkOption]

    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    val sparkSession = context.getSparkSession
    DorisUtils.writeStreamSelectAdapter(
      sparkSession,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def writeStarrocks(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): Unit = {
    val sinkOption =
      context.getSourceOption.asInstanceOf[StarrocksDataTunnelSinkOption]

    val checkpointLocation = sourceOption.getCheckpointLocation
    val triggerProcessingTime = sourceOption.getTriggerProcessingTime
    if (StringUtils.isBlank(checkpointLocation)) {
      throw new IllegalArgumentException("checkpointLocation 不能为空")
    }

    val querySql = buildQuerySql(context, sourceOption, tmpTable)
    val sparkSession = context.getSparkSession
    StarrocksUtils.writeStreamSelectAdapter(
      sparkSession,
      checkpointLocation,
      triggerProcessingTime,
      sinkOption,
      querySql
    )
  }

  private def buildQuerySqlForKafka(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): String = {
    val sql = "select * from " + tmpTable

    val transfromSql = context.getTransfromSql
    if (StringUtils.isNotBlank(transfromSql)) {
      val df = context.getSparkSession.sql(sql)
      df.createTempView(sourceOption.getSourceTempView)
      transfromSql
    } else {
      val df = context.getSparkSession
        .sql(sql)
        .select(to_json(struct("*")).as("value"))
        .selectExpr("CAST(value AS STRING)")

      val tempView = "tdl_datatunnel_kafka_json_" + System.currentTimeMillis()
      df.createTempView(tempView)
      "select * from " + tempView;
    }
  }

  private def buildQuerySql(
      context: DataTunnelContext,
      sourceOption: KafkaDataTunnelSourceOption,
      tmpTable: String
  ): String = {
    val sql = "select * from " + tmpTable

    val transfromSql = context.getTransfromSql
    if (StringUtils.isNotBlank(transfromSql)) {
      val df = context.getSparkSession.sql(sql)
      df.createTempView(sourceOption.getSourceTempView)
      transfromSql
    } else {
      sql
    }
  }

  override def getOptionClass: Class[_ <: DataTunnelSourceOption] =
    classOf[KafkaDataTunnelSourceOption]

}
