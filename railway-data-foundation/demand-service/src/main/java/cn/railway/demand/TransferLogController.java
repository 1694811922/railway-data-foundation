package cn.railway.demand;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class TransferLogController {
    private final TransferLogs logs;
    public TransferLogController(TransferLogs logs){this.logs=logs;}
    @GetMapping("/api/v1/receiving-tasks/transfer-logs")
    public Map<String,Object> page(HttpServletRequest request,@RequestParam(required=false) String taskId,
        @RequestParam(required=false) String eventType,@RequestParam(required=false) String outcome,
        @RequestParam(required=false) String from,@RequestParam(required=false) String until,
        @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        return ReceivingController.ok(logs.page(ReceiverSecurity.identity(request).path("subject_id").asText(),taskId,eventType,outcome,from,until,limit,offset));
    }
}
