package cn.railway.foundation.subscription;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/kafka-subscriptions")
public class SubscriptionController {
    private final SubscriptionService service;
    private final SubscriptionExecutor executor;
    public SubscriptionController(SubscriptionService service,SubscriptionExecutor executor) { this.service=service;this.executor=executor; }
    @GetMapping public ApiResponse<SubscriptionService.Page> list(
            @RequestParam(defaultValue="20") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset,
            @RequestParam(defaultValue="") @Size(max=120) String keyword,
            @RequestParam(defaultValue="") String status) {
        return ApiResponse.success(service.list(limit,offset,keyword,status));
    }
    @GetMapping("/{id}") public ApiResponse<SubscriptionService.Grant> get(@PathVariable String id) { return ApiResponse.success(service.get(id)); }
    @PostMapping public ApiResponse<SubscriptionService.Grant> create(@Valid @RequestBody SubscriptionRequest request) { return ApiResponse.success(service.create(request)); }
    @PutMapping("/{id}") public ApiResponse<SubscriptionService.Grant> update(@PathVariable String id,@Valid @RequestBody SubscriptionUpdateRequest request) { return ApiResponse.success(service.update(id,request)); }
    @PostMapping("/{id}/{action:submit|confirm|return|revoke}") public ApiResponse<SubscriptionService.Grant> action(@PathVariable String id,@PathVariable String action,@Valid @RequestBody SubscriptionActionRequest request) { return ApiResponse.success(service.action(id,action,request)); }
    @PostMapping("/{id}/{action:provider-confirm|provider-return}") public ApiResponse<SubscriptionService.Grant> providerReview(@PathVariable String id,@PathVariable String action,@Valid @RequestBody cn.railway.foundation.sharing.ProviderReviewRequest request) { return ApiResponse.success(service.providerReview(id,action,request)); }
    @GetMapping("/{id}/events") public ApiResponse<List<SubscriptionService.Event>> events(@PathVariable String id) { return ApiResponse.success(service.events(id)); }
    @PostMapping("/{id}/{operation:provision|stop|retry}") public ApiResponse<SubscriptionService.Grant> execute(@PathVariable String id,@PathVariable String operation,@Valid @RequestBody SubscriptionExecutionRequest request) {
        return ApiResponse.success(executor.request(id,operation,request));
    }
    @PostMapping("/{id}/credential") public org.springframework.http.ResponseEntity<ApiResponse<SubscriptionExecutor.Delivery>> credential(@PathVariable String id,@Valid @RequestBody SubscriptionExecutionRequest request) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").body(ApiResponse.success(executor.claim(id,request)));
    }
}
