package cn.railway.foundation.resource;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResourceRequest(
        @NotBlank @Size(max=120) String resourceName,
        @NotBlank String sourceId,
        String sourceName,
        @NotBlank @Size(max=64) String schemaName,
        @NotBlank @Size(max=64) String physicalTable,
        @Pattern(regexp="TABLE") String resourceType,
        @NotBlank @Size(max=2000) String businessDescription,
        @NotBlank @Size(max=120) String ownerDepartment,
        @NotBlank @Pattern(regexp="公开|内部|敏感|机密") String securityLevel,
        @NotBlank @Pattern(regexp="实时|每日|按需") String updateMode) {}
