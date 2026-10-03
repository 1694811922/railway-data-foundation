package cn.railway.foundation.source;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceRequest(
        @NotBlank @Size(max=120) String sourceName,
        @NotBlank @Pattern(regexp="MYSQL") @Schema(example="MYSQL") String sourceType,
        @Size(max=80) @Pattern(regexp="SOURCE_[A-Z][A-Z0-9_]*")
        @Schema(description="仅兼容旧登记；不得与动态连接字段混用")
        String connectionEnvPrefix,
        @Size(max=1000) String description,
        @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9.-]{0,252}") String host,
        @Min(1) @Max(65535) Integer port,
        @Pattern(regexp="[A-Za-z0-9_]{1,64}") String databaseName,
        @Size(max=128) String username,
        @Pattern(regexp="DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY") String sslMode,
        @Size(max=1024) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY,description="新增必填；编辑留空保留原凭据，永不返回") String password,
        @Pattern(regexp="ENABLED|DISABLED") String status) {
    @Override public String toString() { return "SourceRequest[redacted]"; }
}
