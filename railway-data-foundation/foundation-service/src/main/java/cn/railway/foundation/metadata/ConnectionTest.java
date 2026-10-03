package cn.railway.foundation.metadata;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConnectionTest(boolean connected, String databaseProduct, String databaseVersion,
                             long responseTimeMs, String reason, String errorCode) {}
