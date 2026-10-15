package cn.railway.foundation.access;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AccessRequest(
        @NotBlank @Pattern(regexp="REL_[a-f0-9]{32}") String relationshipId,
        @NotBlank @Pattern(regexp="CAP_[a-f0-9]{32}") String capabilityId,
        @Size(max=200) List<@NotBlank @Size(max=64) String> requestedFields,
        @Size(max=200) List<@NotBlank @Size(max=64) String> filterFields,
        Instant from,Instant to) {}
