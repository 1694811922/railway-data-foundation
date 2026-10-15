package cn.railway.foundation.subject;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubjectUpdateRequest(
        @NotBlank @Size(max=120) String subjectName,
        @NotBlank @Size(max=120) String ownerDepartment,
        @Size(max=1000) String description,
        @NotNull @Min(1) Integer version) {
}
