package cn.railway.foundation.capability;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CapabilityRequest(
        @NotBlank @Size(max=120) String capabilityName,
        @NotBlank String resourceId,
        @NotBlank String type,
        @Size(max=8) String httpMethod,
        @Size(max=500) String endpoint,
        @Size(max=249) String topic,
        @Size(max=64) String sourceTable,
        @Size(max=40) String protocol,
        @Size(max=24) String authType,
        @Size(max=10000) String messageSchema,
        @Size(max=120) String queryTemplateCode,
        @Size(max=1000) String description) {}
