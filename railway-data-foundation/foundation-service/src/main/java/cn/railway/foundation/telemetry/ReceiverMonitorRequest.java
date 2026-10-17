package cn.railway.foundation.telemetry;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReceiverMonitorRequest(@NotNull @Size(min=1,max=50) List<@NotNull @Valid Event> events){
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Event(
        @NotNull @Pattern(regexp="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String eventId,
        @NotNull @Pattern(regexp="RCV_[a-f0-9]{32}") String taskId,
        @NotNull @Pattern(regexp="KSG_[a-f0-9]{32}") String grantId,
        @Pattern(regexp="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String batchId,
        @Pattern(regexp="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String reportId,
        @NotNull @Size(max=32) String eventType,@NotNull @Size(max=16) String outcome,@NotNull @Size(max=40) String reasonCode,
        @Pattern(regexp="[A-Za-z0-9_.-]{1,64}") String operatorId,
        @Min(0) @Max(1000) @JsonDeserialize(using=StrictLongDeserializer.class) Long recordCount,
        @Min(0) @Max(1000) @JsonDeserialize(using=StrictLongDeserializer.class) Long insertedCount,
        @Min(0) @Max(1000) @JsonDeserialize(using=StrictLongDeserializer.class) Long duplicateCount,
        @Min(0) @Max(16777215000L) @JsonDeserialize(using=StrictLongDeserializer.class) Long byteCount,
        @NotNull @Size(max=100) List<@NotNull @Valid Position> positions,@NotNull Instant createdAt,
        @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long storageDurationMs){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Position(
        @NotNull @Min(0) @Max(2147483647) @JsonDeserialize(using=StrictLongDeserializer.class) Long partition,
        @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long firstOffset,
        @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long lastOffset,
        @NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long nextOffset){}
}
