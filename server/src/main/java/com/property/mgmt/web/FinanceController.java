package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.FinanceEntry;
import com.property.mgmt.service.FinanceService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;

    @PostMapping("/staff/finance/entries")
    public ApiResponse<FinanceEntry> create(@RequestBody EntryReq req) {
        return ApiResponse.ok(financeService.createEntry(
                req.getEntryType(), req.getAmount(), req.getOccurDate(),
                req.getCategory(), req.getTitle(), req.getRemark(), req.getAttachmentIds()));
    }

    @PutMapping("/staff/finance/entries/{id}")
    public ApiResponse<FinanceEntry> update(@PathVariable Long id, @RequestBody EntryReq req) {
        return ApiResponse.ok(financeService.updateEntry(
                id, req.getEntryType(), req.getAmount(), req.getOccurDate(),
                req.getCategory(), req.getTitle(), req.getRemark()));
    }

    @DeleteMapping("/staff/finance/entries/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        financeService.deleteEntry(id);
        return ApiResponse.ok(null);
    }

    /** 兼容旧客户端：已取消审核，调用后记为已生效 */
    @PostMapping("/staff/finance/entries/{id}/approve")
    public ApiResponse<FinanceEntry> approve(@PathVariable Long id) {
        return ApiResponse.ok(financeService.approve(id));
    }

    @PostMapping("/staff/finance/entries/{id}/reject")
    public ApiResponse<FinanceEntry> reject(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
        return ApiResponse.ok(financeService.reject(id, req == null ? null : req.getReason()));
    }

    @GetMapping("/staff/finance/entries")
    public ApiResponse<Map<String, Object>> listEntries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(financeService.listEntries(from, to, status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/staff/finance/summary")
    public ApiResponse<Map<String, Object>> staffSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "BY_BILL_MONTH") String viewMode) {
        return ApiResponse.ok(financeService.summaryForCurrent("STAFF", from, to, null, viewMode));
    }

    @GetMapping("/resident/finance/summary")
    public ApiResponse<Map<String, Object>> residentSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "BY_BILL_MONTH") String viewMode) {
        return ApiResponse.ok(financeService.summaryForCurrent("RESIDENT", from, to, null, viewMode));
    }

    @GetMapping("/committee/finance/summary")
    public ApiResponse<Map<String, Object>> committeeSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "BY_BILL_MONTH") String viewMode) {
        return ApiResponse.ok(financeService.summaryForCurrent("COMMITTEE", from, to, null, viewMode));
    }

    @GetMapping("/platform/finance/summary")
    public ApiResponse<Map<String, Object>> platformSummary(
            @RequestParam Long communityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "BY_BILL_MONTH") String viewMode) {
        return ApiResponse.ok(financeService.summaryForCurrent("PLATFORM", from, to, communityId, viewMode));
    }

    @Data
    public static class EntryReq {
        private String entryType;
        private BigDecimal amount;
        private LocalDate occurDate;
        private String category;
        private String title;
        private String remark;
        private java.util.List<Long> attachmentIds;
    }

    @Data
    public static class ReasonReq {
        private String reason;
    }
}
