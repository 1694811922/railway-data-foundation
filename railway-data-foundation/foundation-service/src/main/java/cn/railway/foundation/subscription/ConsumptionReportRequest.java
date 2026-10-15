package cn.railway.foundation.subscription;

import cn.railway.foundation.telemetry.StrictLongDeserializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsumptionReportRequest(
    @NotBlank @Pattern(regexp="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String reportId,
    @NotNull Instant startedAt,@NotNull Instant completedAt,
    @NotNull @Size(max=100) List<@NotNull @Valid Position> positions) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Position(
        @NotNull @Min(0) @Max(2147483647) @JsonDeserialize(using=StrictLongDeserializer.class) Long partition,
        @NotNull @Min(0) @JsonDeserialize(using=StrictLongDeserializer.class) Long offset,
        @NotNull @Min(0) @Max(134217728) @JsonDeserialize(using=StrictLongDeserializer.class) Long byteCount){}
}
