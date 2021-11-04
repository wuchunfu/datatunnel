package com.dataworker.datax.jdbc;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dataworker.datax.api.DataxReader;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.util.Map;

/**
 * @author melin 2021/7/27 11:06 上午
 */
public class JdbcReader implements DataxReader {

    @Override
    public void validateOptions(Map<String, String> options) {
        String conf = options.get("__dsConf__");
        JSONObject confMap = JSON.parseObject(conf);
    }

    @Override
    public Dataset<Row> read(SparkSession sparkSession, Map<String, String> options) throws IOException {
        return null;
    }
}
