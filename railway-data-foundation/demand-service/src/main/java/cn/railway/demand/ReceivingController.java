package cn.railway.demand;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @Validated
public class ReceivingController {
    private final ReceivingService service;private final ReceivingProgressService progress;
    public ReceivingController(ReceivingService service,ReceivingProgressService progress){this.service=service;this.progress=progress;}
    public record Create(@NotBlank @Size(max=120) String taskName,@Pattern(regexp="KSG_[a-f0-9]{32}") @NotNull String grantId,@Pattern(regexp="ks_[A-Za-z0-9_-]{43}") @NotNull String password,@Pattern(regexp="rkc_[A-Za-z0-9_-]{43}") @NotNull String consumptionToken,@Pattern(regexp="EARLIEST|LATEST") @NotNull String startPosition){@Override public String toString(){return "Receiving configuration [REDACTED]";}}
    public record Action(@NotNull @Min(1) Integer version){}
    public record Credentials(@NotNull @Min(1) Integer version,@NotNull @Valid Create configuration){@Override public String toString(){return "Receiving credential update [REDACTED]";}}
    static Map<String,Object> ok(Object data){return Map.of("code",0,"message","成功","data",data);}
    String subject(HttpServletRequest r){return ReceiverSecurity.identity(r).path("subject_id").asText();}
    @GetMapping("/api/v1/demand/health") public Map<String,Object> health(){return ok(Map.of("status","UP"));}
    @GetMapping("/api/v1/receiving-tasks") public Map<String,Object> page(HttpServletRequest r,@RequestParam(defaultValue="") @Size(max=120) String keyword,@RequestParam(required=false) @Pattern(regexp="KSG_[a-f0-9]{32}") String grantId,@RequestParam(defaultValue="20") @Min(1) @Max(50) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){return ok(service.page(subject(r),keyword,grantId,limit,offset));}
    @PostMapping("/api/v1/receiving-tasks") public Map<String,Object> create(@Valid @RequestBody Create b,HttpServletRequest r){return ok(service.create(b,r));}
    @GetMapping("/api/v1/receiving-tasks/{id}") public Map<String,Object> get(@PathVariable String id,HttpServletRequest r){return ok(service.get(id,subject(r)));}
    @GetMapping("/api/v1/receiving-tasks/{id}/progress") public Map<String,Object> progress(@PathVariable String id,HttpServletRequest r){return ok(progress.progress(id,subject(r)));}
    @PutMapping("/api/v1/receiving-tasks/{id}/credentials") public Map<String,Object> credentials(@PathVariable String id,@Valid @RequestBody Credentials b,HttpServletRequest r){return ok(service.credentials(id,b.version(),b.configuration(),r));}
    @PostMapping("/api/v1/receiving-tasks/{id}/{action:start|stop|resume}") public Map<String,Object> action(@PathVariable String id,@PathVariable String action,@Valid @RequestBody Action b,HttpServletRequest r){return ok(service.action(id,subject(r),action,b.version()));}
    @GetMapping("/api/v1/receiving-tasks/{id}/messages") public Map<String,Object> messages(@PathVariable String id,HttpServletRequest r,@RequestParam(required=false) @Size(max=249) String topic,@RequestParam(required=false) @Min(0) Integer partition,@RequestParam(required=false) String from,@RequestParam(required=false) String until,@RequestParam(defaultValue="20") @Min(1) @Max(50) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){return ok(service.messages(id,subject(r),topic,partition,from,until,limit,offset));}
    @GetMapping("/api/v1/receiving-tasks/{id}/messages/{message}") public Map<String,Object> message(@PathVariable String id,@PathVariable @Min(1) long message,HttpServletRequest r){return ok(service.message(id,subject(r),message));}
}
