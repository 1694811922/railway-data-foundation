package cn.railway.foundation.subscription;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kafka-subscriptions/{id}")
public class ConsumptionController {
    private final ConsumptionService service;
    public ConsumptionController(ConsumptionService service){this.service=service;}
    @PostMapping("/consumption-reports")
    public org.springframework.http.ResponseEntity<ApiResponse<ConsumptionService.Receipt>> receive(@PathVariable String id,
        @RequestHeader(value="Authorization",required=false) String authorization,@Valid @RequestBody ConsumptionReportRequest body){
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(service.accept(id,authorization,body)));
    }
    @GetMapping("/consumption") public ApiResponse<ConsumptionService.Page> list(@PathVariable String id,
        @RequestParam(defaultValue="20") @Min(1) @Max(200) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset){
        return ApiResponse.success(service.page(id,limit,offset));
    }
}
