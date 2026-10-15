package cn.railway.foundation.sharing;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RelationshipUpdateRequest(
        @NotBlank @Size(max=120) String relationshipName,
        @NotBlank @Size(max=1000) String purpose,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotNull @Valid PolicyRequest policy,
        @NotNull @Min(1) Integer version,
        @NotBlank @Size(max=1000) String reason) {}
