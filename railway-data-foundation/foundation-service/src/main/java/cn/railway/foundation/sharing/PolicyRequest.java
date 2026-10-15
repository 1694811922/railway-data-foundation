package cn.railway.foundation.sharing;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PolicyRequest(
        @NotEmpty @Size(max=200) List<@NotBlank @Size(max=64) String> allowedFields,
        @NotBlank @Size(max=64) String timeField,
        @NotNull Instant dataFrom,
        @NotNull Instant dataUntil) {}
