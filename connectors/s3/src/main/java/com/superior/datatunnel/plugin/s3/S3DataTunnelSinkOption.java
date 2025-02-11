package com.superior.datatunnel.plugin.s3;

import static com.superior.datatunnel.common.enums.FileFormat.*;

import com.superior.datatunnel.common.annotation.OptionDesc;
import com.superior.datatunnel.common.enums.Compression;
import com.superior.datatunnel.common.enums.WriteMode;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class S3DataTunnelSinkOption extends S3CommonOption {

    @OptionDesc("文件压缩，不同文件格式, 支持压缩算法有差异，请参考spark 官方文档")
    private Compression compression;

    @OptionDesc("数据写入模式")
    @NotNull(message = "writeMode can not null")
    private WriteMode writeMode = WriteMode.APPEND;

    @NotNull(message = "path can not blank")
    private String path;

    @OptionDesc("输出文件数量")
    private Integer fileCount;

    public Compression getCompression() {
        if (compression == null) {
            if (this.getFormat() == PARQUET
                    || this.getFormat() == ORC
                    || this.getFormat() == HUDI
                    || this.getFormat() == ICEBERG
                    || this.getFormat() == PAIMON) {
                compression = Compression.ZSTD;
            } else if (this.getFormat() == AVRO) {
                compression = Compression.SNAPPY;
            } else {
                compression = Compression.NONE;
            }
        }

        return compression;
    }
}
