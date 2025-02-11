package com.superior.datatunnel.plugin.hdfs;

import com.superior.datatunnel.api.model.BaseCommonOption;
import com.superior.datatunnel.api.model.DataTunnelSinkOption;
import com.superior.datatunnel.api.model.DataTunnelSourceOption;
import com.superior.datatunnel.common.annotation.OptionDesc;
import com.superior.datatunnel.common.enums.FileFormat;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HdfsCommonOption extends BaseCommonOption implements DataTunnelSourceOption, DataTunnelSinkOption {

    @NotNull(message = "format can not null")
    private FileFormat format;

    @OptionDesc("csv 字段分隔符")
    private String sep = ",";

    @OptionDesc("csv 文件编码")
    private String encoding = "UTF-8";

    @OptionDesc("csv 文件，第一行是否为字段名")
    private boolean header = true;

    @OptionDesc("text 文件，行分隔符")
    private String lineSep;

    private String timestampFormat = "yyyy-MM-dd HH:mm:ss[.SSS]";

    @NotEmpty(message = "columns can not empty")
    private String[] columns = new String[] {"*"};

    @Override
    public String[] getColumns() {
        return columns;
    }
}
