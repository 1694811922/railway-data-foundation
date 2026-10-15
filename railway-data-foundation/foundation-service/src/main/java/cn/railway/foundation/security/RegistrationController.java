package cn.railway.foundation.security;

import cn.railway.foundation.api.ApiResponse;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class RegistrationController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Register(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=120) String displayName,
                           @NotBlank String role,@NotBlank @Size(max=120) String organizationName,@NotBlank @Size(max=72) String password){
        @Override public String toString(){return "Registration[REDACTED]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Bootstrap(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=120) String displayName,@NotBlank @Size(max=72) String password){
        @Override public String toString(){return "Bootstrap[REDACTED]";}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Review(@NotNull @Min(1) Integer version,@NotNull Boolean approve,String subjectId,@NotNull @Size(max=500) String reason){}
    private final RegistrationService registrations;
    public RegistrationController(RegistrationService registrations){this.registrations=registrations;}
    @GetMapping("/auth/registration-options") public ApiResponse<?> options(HttpServletRequest request,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return ApiResponse.success(registrations.availability(request.getRemoteAddr()));}
    @PostMapping("/auth/register") public ApiResponse<?> register(@Valid @RequestBody Register r,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return ApiResponse.success(registrations.submit(r.username(),r.displayName(),r.role(),r.organizationName(),r.password()));}
    @PostMapping("/auth/register-admin") public ApiResponse<?> bootstrap(@Valid @RequestBody Bootstrap r,HttpServletRequest request,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return ApiResponse.success(registrations.bootstrap(r.username(),r.displayName(),r.password(),request.getRemoteAddr()));}
    @GetMapping("/registrations") public ApiResponse<?> list(@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return ApiResponse.success(registrations.list(status,limit,offset));}
    @PostMapping("/registrations/{id}/review") public ApiResponse<?> review(@PathVariable String id,@Valid @RequestBody Review r){registrations.review(id,r.version(),r.approve(),r.subjectId(),r.reason());return ApiResponse.success(null);}
}
