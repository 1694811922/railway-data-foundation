package cn.railway.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;

@Validated
@RestController
public class ContainerEventController {
    public static final String PROVIDER_MARKER="container-event-v1";
    private final ContainerEventService service;
    private final KafkaEventPublisher publisher;
    private final CapabilityRuntimeGuard runtime;
    private final AccessRuntimeGuard access;
    private final SourceTime time;
    public ContainerEventController(ContainerEventService service,KafkaEventPublisher publisher,CapabilityRuntimeGuard runtime,AccessRuntimeGuard access,SourceTime time) {
        this.service=service;
        this.publisher=publisher;
        this.runtime=runtime;
        this.access=access;this.time=time;
    }

    public record Response<T>(int code,String message,T data) {
        static <T> Response<T> success(T data) { return new Response<>(0,"success",data); }
    }

    @GetMapping("/api/v1/provider/health")
    public Response<String> health() { return Response.success("UP"); }

    @RequestMapping(path="/containers/events",method=RequestMethod.HEAD)
    public ResponseEntity<Void> probe() {
        service.probe();
        return ResponseEntity.noContent().header("X-Railway-Provider",PROVIDER_MARKER)
                .header("X-Railway-Runtime","rest-capability-v1").header("X-Railway-Access","sharing-access-v1")
                .header("X-Railway-Source-Host",runtime.sourceHost())
                .header("X-Railway-Source-Port",Integer.toString(runtime.sourcePort()))
                .header("X-Railway-Source-Database",runtime.databaseName())
                .header("X-Railway-Source-Table","container_event").build();
    }

    @GetMapping("/containers/events")
    public Response<List<Map<String,Object>>> events(
            @RequestHeader(value=CapabilityRuntimeGuard.HEADER,required=false) String capabilityId,
            @RequestHeader(value="X-Relationship-Id",required=false) String relationshipId,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(required=false) @Size(max=1000) String fields,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z0-9]{1,20}") String containerNo,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z0-9]{1,30}") String trainNo,
            @RequestParam(required=false) @Pattern(regexp="GATE_IN|GATE_OUT|LOAD|UNLOAD") String eventType,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,HttpServletRequest request) {
        List<String> selected=fields==null?null:Arrays.stream(fields.split(",",-1)).map(String::trim).toList();
        var filters=new java.util.ArrayList<String>();if(containerNo!=null)filters.add("container_no");if(trainNo!=null)filters.add("train_no");if(eventType!=null)filters.add("event_type");
        var capture=RuntimeCapture.from(request);
        var scope=access.require(capabilityId,relationshipId,authorization,selected,filters,from==null?null:from.toInstant(),to==null?null:to.toInstant(),capture);
        var rows=service.searchAuthorized(scope,time,containerNo,trainNo,eventType,limit,offset);
        if(capture!=null)capture.restSuccess(rows.size());return Response.success(rows);
    }

    @PostMapping("/containers/events/{id}/publish")
    public Response<KafkaEventPublisher.Receipt> publish(@PathVariable @Min(1) long id,@RequestHeader(value="X-Provider-Token",required=false) String operatorToken,
            @RequestHeader(value=CapabilityRuntimeGuard.HEADER,required=false) String capabilityId,HttpServletRequest request) {
        try{access.requireOperator(operatorToken);}catch(CapabilityRuntimeGuard.Rejected e){RuntimeCapture.reason(request,e.status().value()==403?"OPERATOR_FORBIDDEN":"CONTROL_UNAVAILABLE");throw e;}
        try {
            var admission=runtime.requireKafkaEnabled(capabilityId);
            var capture=RuntimeCapture.from(request);
            if(capture!=null)capture.governance(new RuntimeObservation.Governance(null,admission.capabilityId(),null,admission.resourceId(),null,null,null));
        } catch(CapabilityRuntimeGuard.Rejected e) {
            RuntimeCapture.reason(request,e.status().value()>=500?"CONTROL_UNAVAILABLE":"CAPABILITY_UNAVAILABLE");throw e;
        }
        return Response.success(publisher.publish(id,RuntimeCapture.from(request)));
    }
}
