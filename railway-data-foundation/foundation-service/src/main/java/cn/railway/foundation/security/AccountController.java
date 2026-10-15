package cn.railway.foundation.security;
import cn.railway.foundation.api.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/accounts")
public class AccountController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(@NotBlank String username,@NotBlank String displayName,@NotBlank String role,String subjectId,@NotBlank String password){@Override public String toString(){return "AccountCreate[REDACTED]";}}
    public record Status(@NotNull @Min(1) Integer version,@NotNull Boolean enabled){}
    public record Password(@NotNull @Min(1) Integer version,@NotBlank String password){@Override public String toString(){return "AccountPassword[REDACTED]";}}
    private final AccountService accounts;
    public AccountController(AccountService accounts){this.accounts=accounts;}
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){return ApiResponse.success(accounts.list(limit,offset));}
    @GetMapping("/catalog") public ApiResponse<?> catalog(
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String role,@RequestParam(required=false) Boolean enabled,
        @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        return ApiResponse.success(accounts.catalog(keyword,role,enabled,limit,offset));
    }
    @GetMapping("/subject-options") public ApiResponse<?> subjects(
        @RequestParam(defaultValue="") String keyword,@RequestParam String role,
        @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        return ApiResponse.success(accounts.subjectOptions(keyword,role,limit,offset));
    }
    @PostMapping public ApiResponse<?> create(@Valid @RequestBody Create r){Actor.requireAdmin();return ApiResponse.success(accounts.create(r.username(),r.displayName(),r.role(),r.subjectId(),r.password()));}
    @PostMapping("/{id}/status") public ApiResponse<?> status(@PathVariable String id,@Valid @RequestBody Status r){accounts.status(id,r.version(),r.enabled());return ApiResponse.success(null);}
    @PostMapping("/{id}/password") public ApiResponse<?> password(@PathVariable String id,@Valid @RequestBody Password r){accounts.password(id,r.version(),r.password());return ApiResponse.success(null);}
}
