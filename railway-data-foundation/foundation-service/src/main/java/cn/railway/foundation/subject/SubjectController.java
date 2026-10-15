package cn.railway.foundation.subject;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {
    private final SubjectService service;
    public SubjectController(SubjectService service) { this.service = service; }
    @GetMapping public ApiResponse<List<SubjectService.Subject>> list(
            @RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.list(limit, offset));
    }
    @GetMapping("/{id}") public ApiResponse<SubjectService.Subject> get(@PathVariable String id) {
        return ApiResponse.success(service.get(id));
    }
    @PostMapping public ApiResponse<SubjectService.Subject> create(@Valid @RequestBody SubjectRequest r) {
        return ApiResponse.success(service.create(r));
    }
    @PutMapping("/{id}") public ApiResponse<SubjectService.Subject> update(@PathVariable String id, @Valid @RequestBody SubjectUpdateRequest r) {
        return ApiResponse.success(service.update(id, r));
    }
    @PostMapping("/{id}/status") public ApiResponse<SubjectService.Subject> status(@PathVariable String id, @Valid @RequestBody SubjectStatusRequest r) {
        return ApiResponse.success(service.status(id, r));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
