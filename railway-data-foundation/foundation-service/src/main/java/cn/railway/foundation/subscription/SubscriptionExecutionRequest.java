package cn.railway.foundation.subscription;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubscriptionExecutionRequest(@NotNull @Min(1) Integer version,
                                           @NotBlank @Size(max=1000) String reason) {}
