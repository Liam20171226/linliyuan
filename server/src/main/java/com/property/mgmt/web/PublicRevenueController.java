package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.PublicRevenueItem;
import com.property.mgmt.service.PublicRevenueService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicRevenueController {

    private final PublicRevenueService publicRevenueService;

    @PostMapping("/staff/public-revenue/items")
    public ApiResponse<PublicRevenueItem> create(@RequestBody ItemReq req) {
        return ApiResponse.ok(publicRevenueService.create(
                req.getTitle(), req.getAmount(), req.getOccurMonth(), req.getRemark()));
    }

    @PutMapping("/staff/public-revenue/items/{id}")
    public ApiResponse<PublicRevenueItem> update(@PathVariable Long id, @RequestBody ItemReq req) {
        return ApiResponse.ok(publicRevenueService.update(
                id, req.getTitle(), req.getAmount(), req.getOccurMonth(), req.getRemark()));
    }

    @DeleteMapping("/staff/public-revenue/items/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        publicRevenueService.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/staff/public-revenue/items")
    public ApiResponse<Map<String, Object>> staffList(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(publicRevenueService.listStaff(status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/resident/public-revenue/items")
    public ApiResponse<Map<String, Object>> residentList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(publicRevenueService.listResident(page, Math.min(pageSize, 100)));
    }

    /** 业委会只读已公示列表（无 confirm/reject） */
    @GetMapping("/committee/public-revenue/items")
    public ApiResponse<Map<String, Object>> committeeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(publicRevenueService.listCommittee(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/platform/public-revenue/items")
    public ApiResponse<Map<String, Object>> platformList(
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(publicRevenueService.listPlatform(
                communityId, status, page, Math.min(pageSize, 100)));
    }

    @Data
    public static class ItemReq {
        private String title;
        private BigDecimal amount;
        private String occurMonth;
        private String remark;
    }
}
