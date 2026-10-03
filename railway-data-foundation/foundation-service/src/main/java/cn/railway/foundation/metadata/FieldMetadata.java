package cn.railway.foundation.metadata;

public record FieldMetadata(String columnName, String dataType, String columnType, boolean nullable,
                            boolean primaryKey, String defaultValue, String columnComment, int ordinalPosition) {}
