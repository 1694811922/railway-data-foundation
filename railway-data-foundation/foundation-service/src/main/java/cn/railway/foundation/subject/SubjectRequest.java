package cn.railway.foundation.subject;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubjectRequest(
        @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,63}") String subjectCode,
        @NotBlank @Size(max=120) String subjectName,
        @NotBlank @Pattern(regexp="PROVIDER|DEMAND") String subjectType,
        @NotBlank @Size(max=120) String ownerDepartment,
        @Size(max=1000) String description) {
}
