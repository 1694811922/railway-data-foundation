package cn.railway.foundation.sharing;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

/** Only an authenticated owning provider may acknowledge the immutable submitted scope. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderReviewRequest(@NotNull @Min(1) Integer version,
                                    @NotBlank @Size(max=1000) String reason,
                                    Boolean contentAcknowledged) {}
