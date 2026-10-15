package cn.railway.foundation.source;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDateTime;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceView(String sourceId, String sourceName, String sourceType,
                         String connectionEnvPrefix, String description,
                         String host, Integer port, String databaseName, String username, String sslMode,
                         String credentialRef, String connectionMode, String status,
                         LocalDateTime createdAt, LocalDateTime updatedAt, String providerSubjectId) {
    public static SourceView from(SourceEntity source) {
        return new SourceView(source.sourceId, source.sourceName, source.sourceType,
                source.connectionEnvPrefix, source.description, source.host, source.port, source.databaseName,
                source.username, source.sslMode, source.credentialRef,
                source.credentialRef==null ? "ENV_REFERENCE" : "DYNAMIC", source.status, source.createdAt, source.updatedAt, source.providerSubjectId);
    }
}
