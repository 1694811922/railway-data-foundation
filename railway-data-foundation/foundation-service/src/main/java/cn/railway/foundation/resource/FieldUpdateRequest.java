package cn.railway.foundation.resource;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FieldUpdateRequest(@Size(max=120) String businessName,
        @Size(max=1000) String businessDescription,
        @NotNull Boolean timeField,
        @Pattern(regexp="公开|内部|敏感|机密") String sensitivityLevel) {}
