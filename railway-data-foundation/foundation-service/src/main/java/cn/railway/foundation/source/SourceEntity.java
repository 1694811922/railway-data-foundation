package cn.railway.foundation.source;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.LocalDateTime;

@TableName("source_system")
public class SourceEntity {
    @TableId public String sourceId;
    public String sourceName;
    public String sourceType;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    public String connectionEnvPrefix;
    public String host;
    public Integer port;
    public String databaseName;
    public String username;
    public String sslMode;
    public String credentialRef;
    public String status;
    public String description;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
