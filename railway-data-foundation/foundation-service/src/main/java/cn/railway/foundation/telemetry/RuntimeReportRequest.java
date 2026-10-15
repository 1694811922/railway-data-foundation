package cn.railway.foundation.telemetry;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RuntimeReportRequest(
        @NotBlank @Pattern(regexp=UUID) String reportId,
        @NotBlank @Pattern(regexp="REST_PROVIDER|KAFKA_PRODUCER") String collectionPoint,
        @NotNull Instant windowStart,@NotNull Instant windowUntil,@NotNull Boolean windowClosed,
        @NotNull @Size(max=200) List<@NotNull @Valid Observation> events) {
    public static final String UUID="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Observation(
            @NotBlank @Pattern(regexp=UUID) String eventId,@NotBlank @Pattern(regexp=UUID) String traceId,
            @Pattern(regexp="REL_[a-f0-9]{32}") String relationshipId,
            @Pattern(regexp="CAP_[a-f0-9]{32}") String capabilityId,
            @Pattern(regexp="SUB_[a-f0-9]{32}") String subjectId,
            @Pattern(regexp="RES_[a-f0-9]{32}") String resourceId,
            @Pattern(regexp="POL_[a-f0-9]{32}") String policyId,
            @Min(1) @Max(Integer.MAX_VALUE) @JsonDeserialize(using=StrictLongDeserializer.class) Long relationshipVersion,
            @Min(1) @Max(Integer.MAX_VALUE) @JsonDeserialize(using=StrictLongDeserializer.class) Long policyVersion,
            @NotNull Instant startedAt,@NotNull Instant completedAt,
            @NotBlank @Pattern(regexp="SUCCESS|DENIED|ERROR") String outcome,
            @NotBlank @Size(max=40) String reasonCode,
            @NotNull @Min(100) @Max(599) @JsonDeserialize(using=StrictLongDeserializer.class) Long httpStatus,
            @NotNull @Min(0) @Max(100) @JsonDeserialize(using=StrictLongDeserializer.class) Long recordCount,
            @NotNull @Min(0) @Max(134217728) @JsonDeserialize(using=StrictLongDeserializer.class) Long byteCount,
            @NotNull @Min(0) @Max(86400000) @JsonDeserialize(using=StrictLongDeserializer.class) Long durationMs,
            @Size(max=249) String topic,
            @Min(0) @Max(Integer.MAX_VALUE) @JsonDeserialize(using=StrictLongDeserializer.class) Long topicPartition,
            @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long topicOffset) {}
}
