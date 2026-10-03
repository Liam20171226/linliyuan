package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.PayReconcileService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PayReconcileController {

    private final PayReconcileService payReconcileService;

    @PostMapping("/staff/pay/reconcile/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) ReconcileReq req) {
        LocalDate date = req == null ? null : req.getDate();
        String channel = req == null ? "ALL" : req.getChannel();
        return ApiResponse.ok(payReconcileService.runDaily(date, channel));
    }

    @GetMapping("/staff/pay/reconcile")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(payReconcileService.list(from, to, page, Math.min(pageSize, 100)));
    }

    @Data
    public static class ReconcileReq {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate date;
        private String channel;
    }
}
