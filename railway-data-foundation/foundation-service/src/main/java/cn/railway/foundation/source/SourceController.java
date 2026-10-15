package cn.railway.foundation.source;

import cn.railway.foundation.api.ApiResponse;
import cn.railway.foundation.metadata.ConnectionTest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/sources")
@Tag(name="数据源管理", description="MySQL动态连接配置；密码仅写入并加密保存，查询永不返回")
public class SourceController {
    private final SourceService service;
    public SourceController(SourceService service) { this.service=service; }

    @PostMapping @Operation(summary="登记数据源")
    public ApiResponse<SourceView> create(@Valid @RequestBody SourceRequest request) { return ApiResponse.success(service.create(request)); }

    @GetMapping @Operation(summary="数据源列表")
    public ApiResponse<List<SourceView>> list(@RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,
                                              @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.list(limit, offset));
    }

    @GetMapping("/{sourceId}") @Operation(summary="数据源详情")
    public ApiResponse<SourceView> get(@PathVariable String sourceId) { return ApiResponse.success(service.get(sourceId)); }

    @PutMapping("/{sourceId}") @Operation(summary="修改数据源")
    public ApiResponse<SourceView> update(@PathVariable String sourceId, @Valid @RequestBody SourceRequest request) {
        return ApiResponse.success(service.update(sourceId, request));
    }

    @PostMapping("/{sourceId}/test") @Operation(summary="真实 JDBC 连接测试", description="连通失败返回 connected=false；配置错误或ID不存在返回对应HTTP错误")
    public ApiResponse<ConnectionTest> test(@PathVariable String sourceId) { return ApiResponse.success(service.test(sourceId)); }

    public record StatusRequest(@NotBlank @Pattern(regexp="ENABLED|DISABLED") String status) {}
    @PatchMapping("/{sourceId}/status") @Operation(summary="启用或停用数据源",description="停用后禁止新的连接测试和扫描，不删除已有扫描结果")
    public ApiResponse<SourceView> status(@PathVariable String sourceId,@Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(service.status(sourceId,request.status()));
    }
    @DeleteMapping("/{sourceId}") @Operation(summary="删除未使用的数据源",description="仅已停用且没有扫描任务的数据源可删除；动态凭据同步清理")
    public ApiResponse<Void> delete(@PathVariable String sourceId) {
        service.delete(sourceId);
        return ApiResponse.success(null);
    }
}
