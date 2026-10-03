package cn.railway.foundation.metadata;

import java.util.List;

public interface MetadataAdapter {
    default boolean testConnection() { return probeConnection().connected(); }
    ConnectionTest probeConnection();
    List<TableMetadata> scanTables();
    List<FieldMetadata> scanFields(String schemaName, String tableName);
}
