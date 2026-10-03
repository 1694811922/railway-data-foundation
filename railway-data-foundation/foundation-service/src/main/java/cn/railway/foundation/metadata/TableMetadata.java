package cn.railway.foundation.metadata;

public record TableMetadata(String schema, String tableName, String tableComment, Long estimatedRows) {}
