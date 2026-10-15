package cn.railway.foundation.sharing;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RelationshipRequest(
        @NotBlank @Size(max=120) String relationshipName,
        @NotBlank @Size(max=36) String providerSubjectId,
        @NotBlank @Size(max=36) String demandSubjectId,
        @NotBlank @Size(max=40) String resourceId,
        @NotBlank @Size(max=40) String capabilityId,
        @NotBlank @Size(max=1000) String purpose,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotNull @Valid PolicyRequest policy,
        @NotBlank @Size(max=1000) String reason) {}
