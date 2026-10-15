package cn.railway.foundation.sharing;

import jakarta.validation.constraints.*;

public record RelationshipActionRequest(
        @NotNull @Min(1) Integer version,
        @NotBlank @Size(max=1000) String reason) {}
