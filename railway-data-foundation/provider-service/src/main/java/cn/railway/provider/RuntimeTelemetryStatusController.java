package cn.railway.provider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local operational counters only. No file paths, addresses, credentials, governance IDs or business data. */
@RestController
public class RuntimeTelemetryStatusController {
    private final RuntimeTelemetryReporter reporter;
    private final AccessRuntimeGuard access;
    public RuntimeTelemetryStatusController(RuntimeTelemetryReporter reporter,AccessRuntimeGuard access){this.reporter=reporter;this.access=access;}
    @GetMapping("/api/v1/provider/telemetry")
    public ContainerEventController.Response<RuntimeTelemetryReporter.Status> status(@org.springframework.web.bind.annotation.RequestHeader(value="X-Provider-Token",required=false) String token){access.requireOperator(token);return ContainerEventController.Response.success(reporter.status());}
}
