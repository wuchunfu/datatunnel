package com.superior.datatunnel.plugin.doris;

import com.superior.datatunnel.api.model.BaseSinkOption;
import com.superior.datatunnel.common.annotation.OptionDesc;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class DorisDataTunnelSinkOption extends BaseSinkOption {

    @OptionDesc("数据库名")
    @NotBlank
    private String databaseName;

    @OptionDesc("表名")
    @NotBlank
    private String tableName;

    @OptionDesc("doris 集群账号的用户名")
    @NotBlank
    private String user;

    @OptionDesc("doris 集群账号的密码")
    @NotBlank
    private String password;

    @OptionDesc("FE 的 HTTP 地址，支持输入多个FE地址，使用逗号分隔")
    @NotBlank
    private String feEnpoints;
}
