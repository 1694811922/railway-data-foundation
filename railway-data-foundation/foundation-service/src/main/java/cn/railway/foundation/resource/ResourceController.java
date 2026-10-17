package cn.railway.foundation.resource;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {
    private final ResourceService service;
    private final ResourceLifecycleService lifecycle;
    public ResourceController(ResourceService service,ResourceLifecycleService lifecycle) { this.service=service;this.lifecycle=lifecycle; }
    @GetMapping public ApiResponse<List<ResourceService.Resource>> list(@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) { return ApiResponse.success(service.list(limit,offset)); }
    @GetMapping("/{id}") public ApiResponse<ResourceService.Resource> get(@PathVariable String id) { return ApiResponse.success(service.get(id)); }
    @PostMapping public ApiResponse<ResourceService.Resource> create(@Valid @RequestBody ResourceRequest request) { return ApiResponse.success(service.create(request)); }
    @PutMapping("/{id}") public ApiResponse<ResourceService.Resource> update(@PathVariable String id,@Valid @RequestBody ResourceRequest request) { return ApiResponse.success(service.update(id,request)); }
    @PostMapping("/{id}/publish") public ApiResponse<ResourceService.Resource> publish(@PathVariable String id,@Valid @RequestBody ResourceActionRequest request) { return ApiResponse.success(lifecycle.change(id,"publish",request).resource()); }
    @GetMapping("/{id}/lifecycle-impact") public ApiResponse<ResourceLifecycleService.Impact> impact(@PathVariable String id){return ApiResponse.success(lifecycle.impact(id));}
    @PostMapping("/{id}/{action:unpublish|republish|retire}") public ApiResponse<ResourceLifecycleService.Result> lifecycle(@PathVariable String id,@PathVariable String action,@Valid @RequestBody ResourceActionRequest request){return ApiResponse.success(lifecycle.change(id,action,request));}
    @GetMapping("/{id}/fields") public ApiResponse<List<ResourceService.Field>> fields(@PathVariable String id) { return ApiResponse.success(service.fields(id)); }
    @PutMapping("/{id}/fields/{fieldName}") public ApiResponse<ResourceService.Field> updateField(@PathVariable String id,@PathVariable String fieldName,
            @Valid @RequestBody FieldUpdateRequest request) { return ApiResponse.success(service.updateField(id,fieldName,request)); }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id,@RequestParam @Min(1) int version) {
        service.delete(id,version);
        return ApiResponse.success(null);
    }
}
