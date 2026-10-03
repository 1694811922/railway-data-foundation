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
    public CapabilityController(CapabilityService service) { this.service=service; }

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
    public ApiResponse<CapabilityService.RestRuntime> runtime(@PathVariable String id) { return ApiResponse.success(service.runtime(id)); }
    @GetMapping("/capabilities/{id}/invocation")
    public ApiResponse<CapabilityService.RestInvocation> invocation(@PathVariable String id) { return ApiResponse.success(service.invocation(id)); }
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
}
