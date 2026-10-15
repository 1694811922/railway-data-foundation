package cn.railway.provider;

import jakarta.servlet.http.*;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.Instant;
import java.util.Map;

/** Direct provider data plane. Source records never pass through foundation-service. */
@RestController
@RequestMapping("/api/v1/production/capabilities/{id}/records")
public class ProductionController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProductionController.class);
    private final ProducerWorkspaceGuard guard;private final ContainerEventService events;private final KafkaEventPublisher publisher;private final SourceTime time;
    public ProductionController(ProducerWorkspaceGuard guard,ContainerEventService events,KafkaEventPublisher publisher,SourceTime time){this.guard=guard;this.events=events;this.publisher=publisher;this.time=time;}
    @GetMapping public ContainerEventController.Response<?> records(@PathVariable String id,
        @RequestParam(required=false) @Size(max=50) String containerNo,@RequestParam(required=false) @Size(max=50) String trainNo,
        @RequestParam(required=false) @Size(max=50) String eventType,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant until,
        @RequestParam(defaultValue="20") @Min(1) @Max(50) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");guard.require(id,request,false);
        return ContainerEventController.Response.success(events.page(containerNo,trainNo,eventType,from==null?null:time.local(from),until==null?null:time.local(until),limit,offset));
    }
    @PostMapping("/{recordId}/send") public ContainerEventController.Response<?> send(@PathVariable String id,@PathVariable @Min(1) long recordId,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");var admission=guard.require(id,request,true);
        var capture=RuntimeCapture.from(request);if(capture!=null)capture.governance(new RuntimeObservation.Governance(null,id,null,admission.resourceId(),null,null,null));
        String outcome="UNCONFIRMED";
        try{if(events.findById(recordId)==null){outcome="NOT_SENT";throw ProducerWorkspaceGuard.rejected(404,"源记录不存在，未发送");}
            var receipt=publisher.publish(recordId,capture);outcome="SUCCESS";return ContainerEventController.Response.success(receipt);
        }finally{LOG.info("Kafka workspace send actor_id={} actor={} capability={} trace={} outcome={}",admission.userId(),admission.username(),id,capture==null?"none":capture.traceId,outcome);}
    }
}
