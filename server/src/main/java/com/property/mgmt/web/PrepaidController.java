package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.PrepaidService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PrepaidController {

    private final PrepaidService prepaidService;

    @PostMapping("/staff/prepaid/plans/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody PlanReq req) {
        return ApiResponse.ok(prepaidService.preview(req.getRoomId(), req.getBillMonths(), req.getFeeCategories()));
    }

    @PostMapping("/staff/prepaid/plans")
    public ApiResponse<Map<String, Object>> create(@RequestBody PlanReq req) {
        return ApiResponse.ok(prepaidService.createPlan(
                req.getRoomId(),
                req.getBillMonths(),
                req.getFeeCategories(),
                req.getCashAmount(),
                req.getPayChannel(),
                req.getRemark(),
                req.getConfirm() == null || Boolean.TRUE.equals(req.getConfirm())));
    }

    @GetMapping("/staff/prepaid/plans")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(prepaidService.listStaffPlans(roomId));
    }

    @GetMapping("/staff/prepaid/plans/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(prepaidService.getStaffPlan(id));
    }

    @PostMapping("/staff/prepaid/plans/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long id) {
        return ApiResponse.ok(prepaidService.confirmPlan(id));
    }

    @PostMapping("/staff/prepaid/plans/{id}/void")
    public ApiResponse<Map<String, Object>> voidPlan(@PathVariable Long id, @RequestBody(required = false) VoidReq req) {
        return ApiResponse.ok(prepaidService.voidPlan(id, req == null ? null : req.getReason()));
    }

    @DeleteMapping("/staff/prepaid/plans/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResponse.ok(prepaidService.deletePlan(id));
    }

    @GetMapping("/resident/prepaid/plans")
    public ApiResponse<Map<String, Object>> residentPlans(@RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(prepaidService.residentOverview(roomId));
    }

    @Data
    public static class PlanReq {
        private Long roomId;
        private List<String> billMonths;
        private List<String> feeCategories;
        private BigDecimal cashAmount;
        private String payChannel;
        private String remark;
        /** 默认 true：创建即生效 */
        private Boolean confirm;
    }

    @Data
    public static class VoidReq {
        private String reason;
    }
}
