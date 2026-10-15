package cn.railway.foundation.access;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/access")
public class AccessController {
    private final AccessService service;
    private final ProviderAuthentication providers;
    public AccessController(AccessService service,ProviderAuthentication providers){this.service=service;this.providers=providers;}
    @PostMapping("/check") public ResponseEntity<ApiResponse<AccessService.Decision>> check(
            @RequestHeader(value="X-Provider-Token",required=false) String providerToken,
            @RequestHeader(value="Authorization",required=false) String callerToken,@Valid @RequestBody AccessRequest request){
        providers.require(providerToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.check(callerToken,request)));
    }
}
