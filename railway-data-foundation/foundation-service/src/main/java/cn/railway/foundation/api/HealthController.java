package cn.railway.foundation.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    @Operation(summary = "服务存活检查", description = "仅报告应用存活，不代表外部Source或Kafka可用。")
    @GetMapping("/health")
    public ApiResponse<Health> health() {
        return ApiResponse.success(new Health("UP", "foundation-service"));
    }

    public record Health(String status, String service) {}
}
