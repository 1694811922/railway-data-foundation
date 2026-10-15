package cn.railway.foundation.sharing;

import cn.railway.foundation.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RelationshipController {
    private final RelationshipService service;
    public RelationshipController(RelationshipService service) {this.service=service;}
    @GetMapping("/relationships") public ApiResponse<List<RelationshipService.Relationship>> list(
            @RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {
        return ApiResponse.success(service.list(limit,offset));
    }
    @GetMapping("/relationships/{id}") public ApiResponse<RelationshipService.Relationship> get(@PathVariable String id) {return ApiResponse.success(service.get(id));}
    @PostMapping("/relationships") public ApiResponse<RelationshipService.Relationship> create(@Valid @RequestBody RelationshipRequest r) {return ApiResponse.success(service.create(r));}
    @PutMapping("/relationships/{id}") public ApiResponse<RelationshipService.Relationship> update(@PathVariable String id,@Valid @RequestBody RelationshipUpdateRequest r) {return ApiResponse.success(service.update(id,r));}
    @PostMapping("/relationships/{id}/{action:submit|confirm|return|enable|suspend|revoke}") public ApiResponse<RelationshipService.Relationship> action(
            @PathVariable String id,@PathVariable String action,@Valid @RequestBody RelationshipActionRequest r) {return ApiResponse.success(service.action(id,action,r));}
    @GetMapping("/relationships/{id}/events") public ApiResponse<List<RelationshipService.Event>> events(@PathVariable String id) {return ApiResponse.success(service.events(id));}
    @PostMapping("/relationships/{id}/{action:provider-confirm|provider-return}") public ApiResponse<RelationshipService.Relationship> providerReview(@PathVariable String id,@PathVariable String action,@Valid @RequestBody ProviderReviewRequest request) {return ApiResponse.success(service.providerReview(id,action,request));}
    @GetMapping("/access-policies") public ApiResponse<List<RelationshipService.Policy>> policies(
            @RequestParam(defaultValue="50") @Min(1) @Max(200) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int offset) {return ApiResponse.success(service.policies(limit,offset));}
    @GetMapping("/access-policies/{id}") public ApiResponse<RelationshipService.Policy> policy(@PathVariable String id) {return ApiResponse.success(service.policy(id));}
}
