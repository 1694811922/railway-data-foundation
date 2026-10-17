package cn.railway.foundation.resource;
import jakarta.validation.constraints.*;
public record ResourceActionRequest(@NotNull @Min(1) Integer version,@NotBlank @Size(max=500) String reason){}
