package cn.railway.foundation.access;

import cn.railway.foundation.api.ApiResponse;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CallerCredentialController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IssueRequest(@NotBlank @Size(max=120) String credentialName,@NotNull Instant expiresAt,@NotBlank @Size(max=1000) String reason) {}
    public record RevokeRequest(@NotNull @Min(1) Integer version,@NotBlank @Size(max=1000) String reason) {}
    private final CallerCredentialService service;
    public CallerCredentialController(CallerCredentialService service){this.service=service;}
    @GetMapping("/caller-credentials") public ApiResponse<List<CallerCredentialService.Credential>> list(
            @RequestParam(required=false) String subjectId,@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){return ApiResponse.success(service.list(subjectId,limit,offset));}
    @GetMapping("/caller-credentials/{id}") public ApiResponse<CallerCredentialService.Credential> get(@PathVariable String id){return ApiResponse.success(service.get(id));}
    @GetMapping("/caller-credentials/{id}/events") public ApiResponse<List<CallerCredentialService.Event>> events(@PathVariable String id){return ApiResponse.success(service.events(id));}
    @PostMapping("/subjects/{id}/credentials") public ResponseEntity<ApiResponse<CallerCredentialService.Issued>> issue(
            @PathVariable String id,@Valid @RequestBody IssueRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.issue(id,request.credentialName(),request.expiresAt(),request.reason())));
    }
    @PostMapping("/caller-credentials/{id}/revoke") public ApiResponse<CallerCredentialService.Credential> revoke(
            @PathVariable String id,@Valid @RequestBody RevokeRequest request){return ApiResponse.success(service.revoke(id,request.version(),request.reason()));}
}
