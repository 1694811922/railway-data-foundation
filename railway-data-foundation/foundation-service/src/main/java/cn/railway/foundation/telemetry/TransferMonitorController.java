package cn.railway.foundation.telemetry;

import cn.railway.foundation.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
public class TransferMonitorController {
    private final TransferMonitorService service;
    public TransferMonitorController(TransferMonitorService service){this.service=service;}
    @GetMapping("/api/v1/transfer-monitor/events")
    public ApiResponse<Map<String,Object>> page(@RequestParam Instant from,@RequestParam Instant until,
        @RequestParam(required=false) String method,@RequestParam(required=false) String stage,@RequestParam(required=false) String outcome,
        @RequestParam(required=false) String resourceId,@RequestParam(required=false) String taskId,@RequestParam(required=false) String traceId,
        @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        return ApiResponse.success(service.page(new TransferMonitorService.Filter(from,until,method,stage,outcome,resourceId,taskId,traceId),limit,offset));
    }
}
