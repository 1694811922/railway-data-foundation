package cn.railway.foundation.capability;

import cn.railway.foundation.api.ApiResponse;
import cn.railway.foundation.security.Actor;
import cn.railway.foundation.security.WorkspaceGate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Login admission only: never queries or accepts source business records. */
@RestController
@RequestMapping("/api/v1/capabilities/{id}/producer-context")
public class ProducerContextController {
    private final CapabilityService capabilities;
    private final WorkspaceGate gate;
    public ProducerContextController(CapabilityService capabilities,WorkspaceGate gate){this.capabilities=capabilities;this.gate=gate;}
    @GetMapping public ApiResponse<?> read(@PathVariable String id){return context(id);}
    @PostMapping public ApiResponse<?> send(@PathVariable String id){return context(id);}
    private ApiResponse<?> context(String id){
        if(!Actor.provider())Actor.deny();
        gate.capability(id);
        var runtime=capabilities.kafkaRuntime(id);
        if(!runtime.enabled())Actor.deny();
        return ApiResponse.success(Map.of("user_id",Actor.userId(),"username",Actor.operator(),"subject_id",Actor.subject(),"runtime",runtime));
    }
}
