package cn.railway.foundation.capability;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CapabilityController {
    private final CapabilityService service;
    private final cn.railway.foundation.access.ProviderAuthentication providers;
    private final cn.railway.foundation.access.CallerCredentialService callers;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public CapabilityController(CapabilityService service,cn.railway.foundation.access.ProviderAuthentication providers,cn.railway.foundation.access.CallerCredentialService callers,org.springframework.jdbc.core.JdbcTemplate db) { this.service=service;this.providers=providers;this.callers=callers;this.db=db; }

    @GetMapping("/capabilities")
    public ApiResponse<List<CapabilityService.Capability>> list(@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.list(limit,offset));
    }
    @GetMapping("/resources/{resourceId}/capabilities")
    public ApiResponse<List<CapabilityService.Capability>> byResource(@PathVariable String resourceId) {
        return ApiResponse.success(service.byResource(resourceId));
    }
    @GetMapping("/capabilities/{id}")
    public ApiResponse<CapabilityService.Capability> get(@PathVariable String id) { return ApiResponse.success(service.get(id)); }
    @GetMapping("/capabilities/{id}/runtime")
    public ApiResponse<CapabilityService.RestRuntime> runtime(@PathVariable String id,@RequestHeader(value="X-Provider-Token",required=false) String token) { control(token);return ApiResponse.success(service.runtime(id)); }
    @GetMapping("/capabilities/{id}/kafka-runtime")
    public ApiResponse<CapabilityService.KafkaRuntime> kafkaRuntime(@PathVariable String id,@RequestHeader(value="X-Provider-Token",required=false) String token) { control(token);return ApiResponse.success(service.kafkaRuntime(id)); }
    @GetMapping("/capabilities/{id}/invocation")
    public ApiResponse<CapabilityService.RestInvocation> invocation(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String authorization,@RequestHeader(value="X-Relationship-Id",required=false) String relationship) {
        if(authorization!=null){
            var identity=callers.authenticate(authorization,java.time.Instant.now());
            if(identity==null)throw new cn.railway.foundation.api.BusinessException(cn.railway.foundation.api.ErrorCode.UNAUTHENTICATED,"调用凭据无效");
            if(db.queryForObject("SELECT COUNT(*) FROM sharing_relationship r JOIN sharing_subject p ON p.subject_id=r.provider_subject_id WHERE r.relationship_id=? AND r.capability_id=? AND r.demand_subject_id=? AND r.status='ACTIVE' AND r.confirmed_at IS NOT NULL AND r.valid_from<=UTC_TIMESTAMP(3) AND r.valid_until>UTC_TIMESTAMP(3) AND p.status='ENABLED'",Integer.class,relationship,id,identity.subjectId())!=1)cn.railway.foundation.security.Actor.deny();
        }
        return ApiResponse.success(service.invocation(id));
    }
    private void control(String token){if(token!=null||cn.railway.foundation.security.Actor.user()==null)providers.require(token);}
    @PostMapping("/capabilities")
    public ApiResponse<CapabilityService.Capability> create(@Valid @RequestBody CapabilityRequest request) {
        return ApiResponse.success(service.create(request));
    }
    @PutMapping("/capabilities/{id}")
    public ApiResponse<CapabilityService.Capability> update(@PathVariable String id,@Valid @RequestBody CapabilityRequest request) {
        return ApiResponse.success(service.update(id,request));
    }
    @PostMapping("/capabilities/{id}/verify")
    public ApiResponse<CapabilityService.Capability> verify(@PathVariable String id) { return ApiResponse.success(service.verify(id)); }
    @PostMapping("/capabilities/{id}/enable")
    public ApiResponse<CapabilityService.Capability> enable(@PathVariable String id) { return ApiResponse.success(service.enable(id)); }
    @PostMapping("/capabilities/{id}/disable")
    public ApiResponse<CapabilityService.Capability> disable(@PathVariable String id) { return ApiResponse.success(service.disable(id)); }
    @DeleteMapping("/capabilities/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) { service.delete(id); return ApiResponse.success(null); }
}
