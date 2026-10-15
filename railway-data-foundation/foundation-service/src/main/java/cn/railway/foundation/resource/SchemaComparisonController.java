package cn.railway.foundation.resource;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/resources/{id}/schema-comparison")
public class SchemaComparisonController {
    private final SchemaComparisonService service;
    public SchemaComparisonController(SchemaComparisonService service) { this.service=service; }
    @GetMapping("/scans")
    public ApiResponse<SchemaComparisonService.Candidates> candidates(@PathVariable String id,
            @RequestParam(defaultValue="20") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.candidates(id,limit,offset));
    }
    @GetMapping
    public ApiResponse<SchemaComparisonService.Comparison> compare(@PathVariable String id,
            @RequestParam @Pattern(regexp="SCAN_[a-f0-9]{32}") String scanTaskId) {
        return ApiResponse.success(service.compare(id,scanTaskId));
    }
}
