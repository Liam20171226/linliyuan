package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.InspectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InspectController {

    private final InspectService inspectService;

    // ---- 位置 ----
    @GetMapping("/staff/inspect/spots")
    public ApiResponse<List<Map<String, Object>>> spots(
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) Long floorId) {
        return ApiResponse.ok(inspectService.listSpots(floorId, buildingId, unitId));
    }

    @PostMapping("/staff/inspect/spots")
    public ApiResponse<Map<String, Object>> createSpot(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inspectService.createSpot(body));
    }

    @PutMapping("/staff/inspect/spots/{id}")
    public ApiResponse<Map<String, Object>> updateSpot(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inspectService.updateSpot(id, body));
    }

    @DeleteMapping("/staff/inspect/spots/{id}")
    public ApiResponse<Void> deleteSpot(@PathVariable Long id) {
        inspectService.deleteSpot(id);
        return ApiResponse.ok();
    }

    @GetMapping("/staff/inspect/spots/{id}/qr")
    public ApiResponse<Map<String, Object>> spotQr(@PathVariable Long id) {
        return ApiResponse.ok(inspectService.spotQrPayload(id));
    }

    @PostMapping("/staff/inspect/spots/qr-batch")
    public ApiResponse<List<Map<String, Object>>> batchQr(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> raw = body == null ? null : (List<Object>) body.get("ids");
        List<Long> ids = new java.util.ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Number n) ids.add(n.longValue());
                else if (o != null) ids.add(Long.parseLong(o.toString()));
            }
        }
        return ApiResponse.ok(inspectService.batchQrPayload(ids));
    }

    // ---- 计划 ----
    @GetMapping("/staff/inspect/plans")
    public ApiResponse<List<Map<String, Object>>> plans(@RequestParam(required = false) String status) {
        return ApiResponse.ok(inspectService.listPlans(status));
    }

    @GetMapping("/staff/inspect/plans/{id}")
    public ApiResponse<Map<String, Object>> plan(@PathVariable Long id) {
        return ApiResponse.ok(inspectService.getPlan(id));
    }

    @PostMapping("/staff/inspect/plans")
    public ApiResponse<Map<String, Object>> createPlan(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inspectService.createPlan(body));
    }

    @PutMapping("/staff/inspect/plans/{id}")
    public ApiResponse<Map<String, Object>> updatePlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inspectService.updatePlan(id, body));
    }

    @PostMapping("/staff/inspect/plans/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id) {
        return ApiResponse.ok(inspectService.publishPlan(id));
    }

    @PostMapping("/staff/inspect/plans/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        inspectService.cancelPlan(id);
        return ApiResponse.ok();
    }

    // ---- 执行任务 ----
    @GetMapping("/staff/inspect/jobs")
    public ApiResponse<Map<String, Object>> myJobs(@RequestParam(required = false) String status) {
        return ApiResponse.ok(inspectService.myJobs(status));
    }

    @GetMapping("/staff/inspect/jobs/{id}")
    public ApiResponse<Map<String, Object>> job(@PathVariable Long id) {
        return ApiResponse.ok(inspectService.getJob(id));
    }

    @PostMapping("/staff/inspect/jobs/{id}/claim")
    public ApiResponse<Map<String, Object>> claim(@PathVariable Long id) {
        return ApiResponse.ok(inspectService.claimJob(id));
    }

    @PostMapping("/staff/inspect/jobs/{id}/scan")
    public ApiResponse<Map<String, Object>> scan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inspectService.scanVisit(id, body));
    }

    // ---- 照片墙 ----
    @GetMapping("/staff/inspect/photos")
    public ApiResponse<Map<String, Object>> photos(
            @RequestParam(required = false) Long planId,
            @RequestParam(required = false) Long jobId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "40") int pageSize) {
        return ApiResponse.ok(inspectService.photoWall(planId, jobId, page, Math.min(pageSize, 100)));
    }
}
