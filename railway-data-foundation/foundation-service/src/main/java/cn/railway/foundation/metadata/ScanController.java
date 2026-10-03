package cn.railway.foundation.metadata;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ScanController {
    private final ScanService service;
    public ScanController(ScanService service) { this.service = service; }

    @PostMapping("/sources/{sourceId}/scan")
    public ApiResponse<ScanService.Task> start(@PathVariable String sourceId) { return ApiResponse.success(service.start(sourceId)); }

    @GetMapping("/scan-tasks")
    public ApiResponse<List<ScanService.Task>> list(@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.list(limit, offset));
    }

    @GetMapping("/scan-tasks/{taskId}")
    public ApiResponse<ScanService.Task> task(@PathVariable String taskId) { return ApiResponse.success(service.task(taskId)); }

    @GetMapping("/scan-tasks/{taskId}/tables")
    public ApiResponse<List<ScanService.Table>> tables(@PathVariable String taskId) { return ApiResponse.success(service.tables(taskId)); }

    @GetMapping("/scan-tasks/{taskId}/tables/{tableName}/fields")
    public ApiResponse<List<ScanService.Field>> fields(@PathVariable String taskId, @PathVariable String tableName) {
        return ApiResponse.success(service.fields(taskId, tableName));
    }
}
