package cn.railway.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@Validated
@RestController
public class ContainerEventController {
    public static final String PROVIDER_MARKER="container-event-v1";
    private final ContainerEventService service;
    private final KafkaEventPublisher publisher;
    private final CapabilityRuntimeGuard runtime;
    public ContainerEventController(ContainerEventService service,KafkaEventPublisher publisher,CapabilityRuntimeGuard runtime) {
        this.service=service;
        this.publisher=publisher;
        this.runtime=runtime;
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
                .header("X-Railway-Runtime","rest-capability-v1")
                .header("X-Railway-Source-Host",runtime.sourceHost())
                .header("X-Railway-Source-Port",Integer.toString(runtime.sourcePort()))
                .header("X-Railway-Source-Database",runtime.databaseName())
                .header("X-Railway-Source-Table","container_event").build();
    }

    @GetMapping("/containers/events")
    public Response<List<ContainerEventService.Event>> events(
            @RequestHeader(value=CapabilityRuntimeGuard.HEADER,required=false) String capabilityId,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z0-9]{1,20}") String containerNo,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z0-9]{1,30}") String trainNo,
            @RequestParam(required=false) @Pattern(regexp="GATE_IN|GATE_OUT|LOAD|UNLOAD") String eventType,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset) {
        runtime.requireEnabled(capabilityId);
        return Response.success(service.search(containerNo,trainNo,eventType,from,to,limit,offset));
    }

    @PostMapping("/containers/events/{id}/publish")
    public Response<KafkaEventPublisher.Receipt> publish(@PathVariable @Min(1) long id) {
        return Response.success(publisher.publish(id));
    }
}
