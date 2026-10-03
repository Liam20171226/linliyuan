package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.PayOrder;
import com.property.mgmt.domain.PayReconcileDay;
import com.property.mgmt.domain.PaymentRecord;
import com.property.mgmt.integration.pay.AggregatorPayClient;
import com.property.mgmt.integration.pay.PayChannel;
import com.property.mgmt.mapper.PayOrderMapper;
import com.property.mgmt.mapper.PayReconcileDayMapper;
import com.property.mgmt.mapper.PaymentRecordMapper;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 按日对账：平台 payment_record（线上渠道） vs 渠道侧汇总（MOCK 用 pay_order 回放）。
 */
@Service
@RequiredArgsConstructor
public class PayReconcileService {

    private final PaymentRecordMapper paymentRecordMapper;
    private final PayOrderMapper payOrderMapper;
    private final PayReconcileDayMapper reconcileDayMapper;
    private final AggregatorPayClient aggregatorPayClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Map<String, Object> runDaily(LocalDate date, String channelRaw) {
        AuthUser staff = StaffGuard.requireStaff();
        StaffGuard.requireManagerOrPlatform(staff);
        Long cid = staff.getCommunityId();
        if (date == null) {
            date = LocalDate.now().minusDays(1);
        }
        String channel = channelRaw == null || channelRaw.isBlank() ? "ALL" : channelRaw.trim().toUpperCase();
        if (!Set.of("WECHAT", "ALIPAY", "ALL").contains(channel)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "channel 须为 WECHAT/ALIPAY/ALL");
        }

        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.atTime(LocalTime.MAX);

        List<String> recordChannels = new ArrayList<>();
        if ("WECHAT".equals(channel) || "ALL".equals(channel)) {
            recordChannels.add("WECHAT_MCH");
        }
        if ("ALIPAY".equals(channel) || "ALL".equals(channel)) {
            recordChannels.add("ALIPAY_MCH");
        }

        List<PaymentRecord> records = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getCommunityId, cid)
                .in(PaymentRecord::getPayChannel, recordChannels)
                .ge(PaymentRecord::getPaidAt, from)
                .le(PaymentRecord::getPaidAt, to));

        BigDecimal platformAmount = BigDecimal.ZERO;
        int platformCount = 0;
        for (PaymentRecord r : records) {
            if (r.getAmount() == null) {
                continue;
            }
            platformAmount = platformAmount.add(r.getAmount());
            if (r.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                platformCount++;
            }
        }
        platformAmount = platformAmount.setScale(2, RoundingMode.HALF_UP);

        // 渠道侧：MOCK 用当日 SUCCESS/REFUNDED pay_order 净额回放；正式接 SDK 后换 fetchDailySummary
        List<String> orderChannels = new ArrayList<>();
        if ("WECHAT".equals(channel) || "ALL".equals(channel)) {
            orderChannels.add("WECHAT");
        }
        if ("ALIPAY".equals(channel) || "ALL".equals(channel)) {
            orderChannels.add("ALIPAY");
        }
        List<PayOrder> orders = payOrderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getCommunityId, cid)
                .in(PayOrder::getChannel, orderChannels)
                .in(PayOrder::getStatus, List.of("SUCCESS", "PARTIAL_REFUND", "REFUNDED"))
                .ge(PayOrder::getPaidAt, from)
                .le(PayOrder::getPaidAt, to));

        BigDecimal channelAmount = BigDecimal.ZERO;
        int channelCount = 0;
        for (PayOrder o : orders) {
            BigDecimal paid = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
            BigDecimal refunded = o.getRefundedAmount() == null ? BigDecimal.ZERO : o.getRefundedAmount();
            channelAmount = channelAmount.add(paid.subtract(refunded));
            channelCount++;
            if (PayChannel.from(o.getChannel()) != null) {
                aggregatorPayClient.fetchDailySummary(
                        PayChannel.from(o.getChannel()), o.getSubMchId(), date.toString());
            }
        }
        channelAmount = channelAmount.setScale(2, RoundingMode.HALF_UP);

        BigDecimal diff = platformAmount.subtract(channelAmount).setScale(2, RoundingMode.HALF_UP);
        String status = diff.compareTo(BigDecimal.ZERO) == 0 ? "MATCHED" : "MISMATCH";

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("platformRecordIds", records.stream().map(PaymentRecord::getId).toList());
        detail.put("payOrderIds", orders.stream().map(PayOrder::getId).toList());
        detail.put("mock", aggregatorPayClient.isMock());

        PayReconcileDay existing = reconcileDayMapper.selectOne(new LambdaQueryWrapper<PayReconcileDay>()
                .eq(PayReconcileDay::getCommunityId, cid)
                .eq(PayReconcileDay::getReconcileDate, date)
                .eq(PayReconcileDay::getChannel, channel)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        PayReconcileDay row = existing == null ? new PayReconcileDay() : existing;
        row.setCommunityId(cid);
        row.setReconcileDate(date);
        row.setChannel(channel);
        row.setPlatformCount(platformCount);
        row.setPlatformAmount(platformAmount);
        row.setChannelCount(channelCount);
        row.setChannelAmount(channelAmount);
        row.setDiffAmount(diff);
        row.setStatus(status);
        try {
            row.setDetailJson(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            row.setDetailJson("{}");
        }
        row.setCreatedBy(staff.getUserId());
        if (existing == null) {
            row.setCreatedAt(now);
            reconcileDayMapper.insert(row);
        } else {
            reconcileDayMapper.updateById(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("reconcileDate", date.toString());
        data.put("channel", channel);
        data.put("platformCount", platformCount);
        data.put("platformAmount", platformAmount);
        data.put("channelCount", channelCount);
        data.put("channelAmount", channelAmount);
        data.put("diffAmount", diff);
        data.put("status", status);
        data.put("mock", aggregatorPayClient.isMock());
        return data;
    }

    public Map<String, Object> list(LocalDate from, LocalDate to, int page, int pageSize) {
        AuthUser staff = StaffGuard.requireStaff();
        Long cid = staff.getCommunityId();
        LambdaQueryWrapper<PayReconcileDay> q = new LambdaQueryWrapper<PayReconcileDay>()
                .eq(PayReconcileDay::getCommunityId, cid)
                .orderByDesc(PayReconcileDay::getReconcileDate)
                .orderByDesc(PayReconcileDay::getId);
        if (from != null) {
            q.ge(PayReconcileDay::getReconcileDate, from);
        }
        if (to != null) {
            q.le(PayReconcileDay::getReconcileDate, to);
        }
        long total = reconcileDayMapper.selectCount(q);
        int offset = Math.max(0, (page - 1) * pageSize);
        q.last("LIMIT " + pageSize + " OFFSET " + offset);
        List<PayReconcileDay> list = reconcileDayMapper.selectList(q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("list", list);
        return data;
    }
}
