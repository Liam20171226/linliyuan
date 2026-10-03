package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.Bill;
import com.property.mgmt.domain.BillMeterUpload;
import com.property.mgmt.domain.FeeItem;
import com.property.mgmt.domain.FeeItemPriceRule;
import com.property.mgmt.security.StaffGuard;
import com.property.mgmt.service.BillingService;
import com.property.mgmt.service.FeeItemService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BillingController {

    private final FeeItemService feeItemService;
    private final BillingService billingService;
    private final com.property.mgmt.service.RoomFeeExemptionService roomFeeExemptionService;

    // ---- fee items ----
    @GetMapping("/staff/fee-items")
    public ApiResponse<List<FeeItem>> listFeeItems() {
        return ApiResponse.ok(feeItemService.list());
    }

    @PostMapping("/staff/fee-items")
    public ApiResponse<FeeItem> createFeeItem(@RequestBody FeeItemReq req) {
        return ApiResponse.ok(feeItemService.create(
                req.getName(), req.getFeeCategory(), req.getBillingMode(),
                req.getCalcType(), req.getMonthlyAmount(), req.getRemark()));
    }

    @PutMapping("/staff/fee-items/{id}")
    public ApiResponse<FeeItem> updateFeeItem(@PathVariable Long id, @RequestBody FeeItemReq req) {
        return ApiResponse.ok(feeItemService.update(
                id, req.getName(), req.getBillingMode(), req.getMonthlyAmount(),
                req.getStatus(), req.getRemark(), req.getCalcType()));
    }

    @DeleteMapping("/staff/fee-items/{id}")
    public ApiResponse<Void> deleteFeeItem(@PathVariable Long id) {
        feeItemService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/staff/fee-items/{id}/price-rules")
    public ApiResponse<List<FeeItemPriceRule>> listRules(@PathVariable Long id) {
        return ApiResponse.ok(feeItemService.listRules(id));
    }

    @PostMapping("/staff/fee-items/{id}/price-rules")
    public ApiResponse<FeeItemPriceRule> addRule(@PathVariable Long id, @RequestBody PriceRuleReq req) {
        return ApiResponse.ok(feeItemService.addRule(id, req.getMatchKey(), req.getUnitPrice()));
    }

    @PutMapping("/staff/fee-item-price-rules/{id}")
    public ApiResponse<FeeItemPriceRule> updateRule(@PathVariable Long id, @RequestBody PriceRuleReq req) {
        return ApiResponse.ok(feeItemService.updateRule(id, req.getMatchKey(), req.getUnitPrice()));
    }

    // ---- meters ----
    @PostMapping("/staff/bill-meter-uploads")
    public ApiResponse<List<BillMeterUpload>> uploadMeters(@RequestBody MeterUploadReq req) {
        List<Map<String, Object>> items = req.getItems();
        if ((items == null || items.isEmpty()) && req.getRoomId() != null) {
            Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("roomId", req.getRoomId());
            one.put("billMonth", req.getBillMonth());
            one.put("feeCategory", req.getFeeCategory());
            one.put("amount", req.getAmount());
            one.put("meterStart", req.getMeterStart());
            one.put("meterEnd", req.getMeterEnd());
            one.put("unitPrice", req.getUnitPrice());
            items = List.of(one);
        }
        return ApiResponse.ok(billingService.uploadMeters(items));
    }

    @PostMapping("/staff/bill-meter-uploads/import")
    public ApiResponse<Map<String, Object>> importMeters(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(billingService.importMetersExcel(file));
    }

    @GetMapping("/staff/bill-meter-uploads/template")
    public ResponseEntity<Resource> meterTemplate(@RequestParam(required = false) String billMonth) {
        Resource file = billingService.meterTemplate(billMonth);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bill-meter-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/staff/bill-meter-uploads")
    public ApiResponse<List<BillMeterUpload>> listMeters(
            @RequestParam(required = false) String billMonth,
            @RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(billingService.listMeters(billMonth, roomId));
    }

    // ---- bills ----
    @PostMapping("/staff/bills/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody BillMonthReq req) {
        return ApiResponse.ok(billingService.generate(req.getBillMonth(), req.getRoomIds()));
    }

    @PostMapping("/staff/bills/publish")
    public ApiResponse<Map<String, Object>> publishBatch(@RequestBody BillMonthReq req) {
        return ApiResponse.ok(billingService.publishBatch(req.getBillMonth(), req.getRoomIds()));
    }

    @PostMapping("/staff/bills/{id}/publish")
    public ApiResponse<Bill> publishOne(@PathVariable Long id) {
        return ApiResponse.ok(billingService.publishOne(id));
    }

    @PostMapping("/staff/rooms/{roomId}/bills/republish")
    public ApiResponse<Bill> republish(@PathVariable Long roomId, @RequestBody BillMonthReq req) {
        return ApiResponse.ok(billingService.republishRoom(roomId, req.getBillMonth()));
    }

    @GetMapping("/staff/bills/charge-preview")
    public ApiResponse<Map<String, Object>> chargePreview(@RequestParam String billMonth) {
        return ApiResponse.ok(billingService.chargePreview(billMonth));
    }

    @GetMapping("/staff/bills")
    public ApiResponse<Map<String, Object>> staffBills(
            @RequestParam(required = false) String billMonth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long roomId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.staffList(billMonth, status, roomId, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/staff/bills/{id}")
    public ApiResponse<Map<String, Object>> staffBill(@PathVariable Long id) {
        return ApiResponse.ok(billingService.staffGet(id));
    }

    @GetMapping("/resident/bills")
    public ApiResponse<Map<String, Object>> residentBills(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.residentList(status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/resident/bills/{id}")
    public ApiResponse<Map<String, Object>> residentBill(@PathVariable Long id) {
        return ApiResponse.ok(billingService.residentGet(id));
    }

    @GetMapping("/platform/bills")
    public ApiResponse<Map<String, Object>> platformBills(
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) String billMonth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.platformList(communityId, billMonth, page, Math.min(pageSize, 100)));
    }

    // ---- payment ----
    @GetMapping("/staff/payment-config")
    public ApiResponse<Map<String, Object>> getPayConfig() {
        return ApiResponse.ok(billingService.getPaymentConfig());
    }

    @PutMapping("/staff/payment-config")
    public ApiResponse<Map<String, Object>> putPayConfig(@RequestBody PayConfigReq req) {
        return ApiResponse.ok(billingService.putPaymentConfig(
                req.getWechatEnabled(), req.getAlipayEnabled(),
                req.getWechatSubMchId(), req.getAlipaySmid(),
                req.getWechatAppId(), req.getWechatApiV3Key(),
                req.getWechatMchSerialNo(), req.getWechatPrivateKeyPem(),
                req.getAlipayPrivateKey(), req.getAlipayPublicKey(),
                req.getOnboardingRemark()));
    }

    @PostMapping("/resident/bills/{id}/pay")
    public ApiResponse<Map<String, Object>> onlinePay(@PathVariable Long id, @RequestBody(required = false) PayChannelReq req) {
        String channel = req == null ? "WECHAT" : req.getChannel();
        return ApiResponse.ok(billingService.onlinePay(id, channel));
    }

    @PostMapping("/resident/bills/{id}/wechat-pay")
    public ApiResponse<Map<String, Object>> wechatPay(@PathVariable Long id) {
        return ApiResponse.ok(billingService.wechatPay(id));
    }

    @PostMapping("/pay/wechat/notify")
    public ApiResponse<Map<String, Object>> wechatNotify(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(billingService.wechatNotify(body));
    }

    @PostMapping("/pay/alipay/notify")
    public ApiResponse<Map<String, Object>> alipayNotify(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(billingService.payNotify("ALIPAY", body));
    }

    @PostMapping("/pay/notify/{channel}")
    public ApiResponse<Map<String, Object>> payNotify(@PathVariable String channel,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(billingService.payNotify(channel, body));
    }

    @GetMapping("/resident/bills/{id}/pay-guide")
    public ApiResponse<Map<String, Object>> payGuide(@PathVariable Long id) {
        return ApiResponse.ok(billingService.payGuide(id));
    }

    @GetMapping("/resident/pay-options")
    public ApiResponse<Map<String, Object>> payOptions() {
        return ApiResponse.ok(billingService.residentPayOptions());
    }

    @PostMapping("/staff/bills/{id}/confirm-paid")
    public ApiResponse<Bill> confirmPaid(@PathVariable Long id, @RequestBody(required = false) ConfirmPaidReq req) {
        String channel = req == null ? null : req.getPayChannel();
        String remark = req == null ? null : req.getRemark();
        return ApiResponse.ok(billingService.confirmPaid(id, channel, remark));
    }

    @PostMapping("/staff/bills/batch-confirm-paid")
    public ApiResponse<Map<String, Object>> batchConfirmPaid(@RequestBody BatchBillsReq req) {
        return ApiResponse.ok(billingService.batchConfirmPaid(
                req.getBillIds(), req.getPayChannel(), req.getRemark()));
    }

    @PostMapping("/resident/bills/batch-wechat-pay")
    public ApiResponse<Map<String, Object>> batchWechatPay(@RequestBody BatchBillsReq req) {
        return ApiResponse.ok(billingService.batchWechatPay(req.getBillIds()));
    }

    @PostMapping("/resident/bills/batch-pay")
    public ApiResponse<Map<String, Object>> batchOnlinePay(@RequestBody BatchPayReq req) {
        return ApiResponse.ok(billingService.batchOnlinePay(req.getBillIds(), req.getChannel()));
    }

    @PostMapping("/staff/bills/{id}/void")
    public ApiResponse<Bill> voidBill(@PathVariable Long id, @RequestBody(required = false) ConfirmPaidReq req) {
        return ApiResponse.ok(billingService.voidUnpaid(id, req == null ? null : req.getRemark()));
    }

    @PostMapping("/staff/bills/{id}/credit-reverse")
    public ApiResponse<Bill> creditReverse(@PathVariable Long id, @RequestBody(required = false) ConfirmPaidReq req) {
        String reason = req == null ? null : req.getRemark();
        return ApiResponse.ok(billingService.creditReverse(id, reason));
    }

    @PostMapping("/staff/bills/{id}/supplement")
    public ApiResponse<?> supplement(@PathVariable Long id, @RequestBody SupplementReq req) {
        return ApiResponse.ok(billingService.supplementPayment(
                id, req.getAmount(), req.getPayChannel(), req.getRemark()));
    }

    @PostMapping("/staff/bills/{id}/refund-duplicate")
    public ApiResponse<Bill> refund(@PathVariable Long id, @RequestBody(required = false) ConfirmPaidReq req) {
        return ApiResponse.ok(billingService.refundDuplicate(id, req == null ? null : req.getRemark()));
    }

    // ---- room fee exemption ----
    @GetMapping("/staff/room-fee-exemptions")
    public ApiResponse<List<com.property.mgmt.domain.RoomFeeExemption>> listExemptions(
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String billMonth) {
        return ApiResponse.ok(roomFeeExemptionService.list(roomId, billMonth));
    }

    @PostMapping("/staff/room-fee-exemptions")
    public ApiResponse<com.property.mgmt.domain.RoomFeeExemption> createExemption(@RequestBody ExemptionReq req) {
        return ApiResponse.ok(roomFeeExemptionService.create(
                req.getRoomId(), req.getFeeCategory(), req.getFeeItemId(),
                req.getEffectiveFrom(), req.getEffectiveTo(), req.getReason()));
    }

    @DeleteMapping("/staff/room-fee-exemptions/{id}")
    public ApiResponse<Void> deleteExemption(@PathVariable Long id) {
        roomFeeExemptionService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/staff/fee-exemption-ledger")
    public ApiResponse<List<com.property.mgmt.domain.FeeExemptionLedger>> listLedger(
            @RequestParam(required = false) String billMonth,
            @RequestParam(required = false) Long roomId) {
        return ApiResponse.ok(roomFeeExemptionService.listLedger(billMonth, roomId));
    }

    @GetMapping("/resident/payment-records")
    public ApiResponse<Map<String, Object>> residentPayments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.residentPaymentRecords(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/staff/payment-records")
    public ApiResponse<Map<String, Object>> staffPayments(
            @RequestParam(required = false) Long billId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.listPaymentRecords(
                StaffGuard.communityId(), billId, page, Math.min(pageSize, 100), true));
    }

    @GetMapping("/platform/payment-records")
    public ApiResponse<Map<String, Object>> platformPayments(
            @RequestParam(required = false) Long communityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var u = com.property.mgmt.security.AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw com.property.mgmt.common.BizException.of(
                    com.property.mgmt.common.ErrorCodes.FORBIDDEN, "需要平台身份");
        }
        return ApiResponse.ok(billingService.listPaymentRecords(
                communityId, null, page, Math.min(pageSize, 100), true));
    }

    @Data
    public static class FeeItemReq {
        private String name;
        private String feeCategory;
        /** FIXED | FORMULA | IMPORT */
        private String billingMode;
        private String calcType;
        private BigDecimal monthlyAmount;
        private Integer status;
        private String remark;
    }

    @Data
    public static class PriceRuleReq {
        private String matchKey;
        private BigDecimal unitPrice;
    }

    @Data
    public static class MeterUploadReq {
        private List<Map<String, Object>> items;
        private Long roomId;
        private String billMonth;
        private String feeCategory;
        private BigDecimal amount;
        private BigDecimal meterStart;
        private BigDecimal meterEnd;
        private BigDecimal unitPrice;
    }

    @Data
    public static class BillMonthReq {
        private String billMonth;
        private List<Long> roomIds;
    }

    @Data
    public static class PayConfigReq {
        private Boolean wechatEnabled;
        private Boolean alipayEnabled;
        private String wechatSubMchId;
        private String alipaySmid;
        private String wechatAppId;
        private String wechatApiV3Key;
        private String wechatMchSerialNo;
        private String wechatPrivateKeyPem;
        private String alipayPrivateKey;
        private String alipayPublicKey;
        private String onboardingRemark;
    }

    @Data
    public static class PayChannelReq {
        private String channel;
    }

    @Data
    public static class BatchPayReq {
        private List<Long> billIds;
        private String channel;
        private String payChannel;
        private String remark;
    }

    @Data
    public static class ConfirmPaidReq {
        private String payChannel;
        private String remark;
    }

    @Data
    public static class BatchBillsReq {
        private List<Long> billIds;
        private String payChannel;
        private String remark;
    }

    @Data
    public static class SupplementReq {
        private BigDecimal amount;
        private String payChannel;
        private String remark;
    }

    @Data
    public static class ExemptionReq {
        private Long roomId;
        private String feeCategory;
        private Long feeItemId;
        private String effectiveFrom;
        private String effectiveTo;
        private String reason;
    }
}
