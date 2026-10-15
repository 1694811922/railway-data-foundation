package cn.railway.foundation.security;
import cn.railway.foundation.api.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccountService accounts;
    public AuthController(AccountService accounts){this.accounts=accounts;}
    @GetMapping("/csrf") public ApiResponse<?> csrf(CsrfToken token){return ApiResponse.success(Map.of("token",token.getToken(),"header_name",token.getHeaderName()));}
    @GetMapping("/me") public ApiResponse<?> me(){return ApiResponse.success(accounts.view(accounts.loadUserByUsername(Actor.user().username)));}
    @PostMapping("/receiver-session") public ApiResponse<?> receiverSession(){if(!Actor.demand())Actor.deny();return ApiResponse.success(accounts.view(accounts.loadUserByUsername(Actor.user().username)));}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PasswordChange(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(max=72) String newPassword){
        @Override public String toString(){return "PasswordChange[REDACTED]";}
    }
    @PostMapping("/password") public ApiResponse<?> password(@Valid @RequestBody PasswordChange body,HttpServletRequest request,HttpServletResponse response){
        accounts.changeOwnPassword(body.currentPassword(),body.newPassword());
        var auth=SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request,response,auth);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request,response,auth);
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(null);
    }
}
