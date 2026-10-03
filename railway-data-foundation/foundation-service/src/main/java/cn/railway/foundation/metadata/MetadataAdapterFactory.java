package cn.railway.foundation.metadata;

import cn.railway.foundation.api.BusinessException;
import cn.railway.foundation.api.ErrorCode;
import cn.railway.foundation.source.SourceEntity;
import org.springframework.stereotype.Component;

@Component
public class MetadataAdapterFactory {
    private final SourceConnectionFactory connections;
    public MetadataAdapterFactory(SourceConnectionFactory connections) { this.connections=connections; }

    public MetadataAdapter create(SourceEntity source) {
        if (!"MYSQL".equals(source.sourceType)) throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "当前仅支持MYSQL数据源");
        connections.validate(source);
        return new MysqlMetadataAdapter(source,connections.configuration(source).databaseName,connections);
    }
}
