package cn.railway.foundation.subject;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubjectStatusRequest(
        @NotBlank @Pattern(regexp="ENABLED|DISABLED") String status,
        @NotNull @Min(1) Integer version) {
}
