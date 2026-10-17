package cn.railway.foundation.telemetry;
import cn.railway.foundation.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ReceiverMonitorController {
    private final ReceiverMonitorService service;
    public ReceiverMonitorController(ReceiverMonitorService service){this.service=service;}
    @PostMapping("/api/v1/telemetry/receiver-reports")
    public ApiResponse<Map<String,Object>> report(@RequestHeader(value="X-Receiver-Telemetry-Token",required=false) String token,@RequestBody ReceiverMonitorRequest request){service.authenticate(token);return ApiResponse.success(service.accept(request));}
    @GetMapping("/api/v1/kafka-transfer-events")
    public ApiResponse<Map<String,Object>> page(@RequestParam(required=false) String grantId,@RequestParam(required=false) String taskId,@RequestParam(required=false) String eventType,@RequestParam(required=false) String outcome,@RequestParam(required=false) String from,@RequestParam(required=false) String until,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return ApiResponse.success(service.page(grantId,taskId,eventType,outcome,from,until,limit,offset));}
}
