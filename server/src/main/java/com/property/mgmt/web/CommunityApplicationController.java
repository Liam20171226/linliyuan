package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.CommunityApplicationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommunityApplicationController {

    private final CommunityApplicationService service;

    @GetMapping("/regions")
    public ApiResponse<List<Map<String, Object>>> regions() {
        return ApiResponse.ok(service.regions());
    }

    @GetMapping("/community-templates")
    public ApiResponse<List<Map<String, Object>>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @PostMapping("/community-applications")
    public ApiResponse<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.submit(body));
    }

    @GetMapping("/community-applications/mine")
    public ApiResponse<Map<String, Object>> mine() {
        return ApiResponse.ok(service.mine());
    }

    @GetMapping("/platform/community-applications")
    public ApiResponse<Map<String, Object>> platformList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.platformList(status, page, Math.min(pageSize, 100)));
    }

    @PostMapping("/platform/community-applications/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable Long id) {
        return ApiResponse.ok(service.approve(id));
    }

    @PostMapping("/platform/community-applications/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody RejectReq req) {
        service.reject(id, req == null ? null : req.getRejectReason());
        return ApiResponse.ok();
    }

    @Data
    public static class RejectReq {
        private String rejectReason;
    }
}
