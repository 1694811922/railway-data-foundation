package cn.railway.foundation.resource;
import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/resources/{id}/versions")
public class ResourceHistoryController {
    private final ResourceHistoryService service;
    public ResourceHistoryController(ResourceHistoryService service){this.service=service;}
    @GetMapping public ApiResponse<ResourceHistoryService.Page> list(@PathVariable String id,
            @RequestParam(defaultValue="20") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){return ApiResponse.success(service.list(id,limit,offset));}
    @GetMapping("/{version}") public ApiResponse<ResourceHistoryService.Detail> detail(@PathVariable String id,@PathVariable @Min(1) int version){return ApiResponse.success(service.detail(id,version));}
}
