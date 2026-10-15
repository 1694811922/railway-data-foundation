package cn.railway.foundation.telemetry;

import cn.railway.foundation.access.ProviderAuthentication;
import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RuntimeTelemetryController {
    private final RuntimeTelemetryService service;
    private final ProviderAuthentication authentication;
    public RuntimeTelemetryController(RuntimeTelemetryService service,ProviderAuthentication authentication){this.service=service;this.authentication=authentication;}
    @PostMapping("/telemetry/reports") public ApiResponse<RuntimeTelemetryService.Receipt> receive(
            @RequestHeader(value="X-Provider-Token",required=false) String token,@Valid @RequestBody RuntimeReportRequest body){
        authentication.require(token);return ApiResponse.success(service.accept(body));
    }
    @GetMapping("/runtime-events") public ApiResponse<RuntimeTelemetryService.Page> list(
            @RequestParam(required=false) String resourceId,@RequestParam(required=false) String capabilityId,
            @RequestParam(required=false) String relationshipId,@RequestParam(required=false) String subjectId,
            @RequestParam(required=false) String outcome,@RequestParam(required=false) String traceId,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant until,
            @RequestParam(required=false) String collectionPoint,
            @RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){
        return ApiResponse.success(service.list(new RuntimeTelemetryService.Filter(resourceId,capabilityId,relationshipId,subjectId,outcome,traceId,from,until,collectionPoint),limit,offset));
    }
    @GetMapping("/runtime-events/{id}") public ApiResponse<RuntimeTelemetryService.Event> get(@PathVariable String id){return ApiResponse.success(service.get(id));}
    @GetMapping("/runtime-traces/{id}") public ApiResponse<List<RuntimeTelemetryService.Event>> trace(@PathVariable String id){return ApiResponse.success(service.trace(id));}
    @GetMapping("/metrics/windows") public ApiResponse<List<RuntimeTelemetryService.Window>> windows(
            @RequestParam String collectionPoint,@RequestParam(required=false) String resourceId,@RequestParam(required=false) String capabilityId,
            @RequestParam(required=false) String relationshipId,@RequestParam(required=false) String subjectId,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant until){
        return ApiResponse.success(service.windows(new RuntimeTelemetryService.Filter(resourceId,capabilityId,relationshipId,subjectId,null,null,from,until,collectionPoint)));
    }
}
