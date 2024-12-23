package com.superior.datatunnel.plugin.hdfs;

import com.superior.datatunnel.api.DataTunnelContext;
import com.superior.datatunnel.api.DataTunnelSource;
import com.superior.datatunnel.api.model.DataTunnelSourceOption;
import com.superior.datatunnel.common.enums.FileFormat;
import java.io.IOException;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.sql.DataFrameReader;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

public class HdfsDataTunnelSource implements DataTunnelSource {

    @Override
    public Dataset<Row> read(DataTunnelContext context) throws IOException {
        HdfsDataTunnelSourceOption sourceOption = (HdfsDataTunnelSourceOption) context.getSourceOption();

        String format = sourceOption.getFormat().name().toLowerCase();
        if (FileFormat.EXCEL == sourceOption.getFormat()) {
            format = "com.crealytics.spark.excel";
        }

        SparkSession sparkSession = context.getSparkSession();
        DataFrameReader reader = sparkSession.read().format(format);
        sourceOption.getProperties().forEach(reader::option);

        if ("csv".equalsIgnoreCase(format)) {
            reader.option("sep", sourceOption.getSep());
            reader.option("encoding", sourceOption.getEncoding());
            reader.option("header", sourceOption.isHeader());
        }
        if (StringUtils.isNotBlank(sourceOption.getLineSep())) {
            reader.option("lineSep", sourceOption.getLineSep());
        }

        return reader.load(sourceOption.getPaths());
    }

    @Override
    public Class<? extends DataTunnelSourceOption> getOptionClass() {
        return HdfsDataTunnelSourceOption.class;
    }
}
