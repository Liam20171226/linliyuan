package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.config.PayProperties;
import com.property.mgmt.config.WxProperties;
import com.property.mgmt.domain.*;
import com.property.mgmt.integration.pay.AggregatorPayClient;
import com.property.mgmt.integration.pay.PayChannel;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多物业子商户线上支付：下单路由、回调幂等入账。
 */
@Service
public class OnlinePayService {

    private final PayOrderMapper payOrderMapper;
    private final PaymentConfigMapper paymentConfigMapper;
    private final BillMapper billMapper;
    private final UserWechatMapper userWechatMapper;
    private final AggregatorPayClient aggregatorPayClient;
    private final PayProperties payProperties;
    private final WxProperties wxProperties;
    private final BillingService billingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OnlinePayService(
            PayOrderMapper payOrderMapper,
            PaymentConfigMapper paymentConfigMapper,
            BillMapper billMapper,
            UserWechatMapper userWechatMapper,
            AggregatorPayClient aggregatorPayClient,
            PayProperties payProperties,
            WxProperties wxProperties,
            @Lazy BillingService billingService) {
        this.payOrderMapper = payOrderMapper;
        this.paymentConfigMapper = paymentConfigMapper;
        this.billMapper = billMapper;
        this.userWechatMapper = userWechatMapper;
        this.aggregatorPayClient = aggregatorPayClient;
        this.payProperties = payProperties;
        this.wxProperties = wxProperties;
        this.billingService = billingService;
    }

    public Map<String, Object> payOptionsForCommunity(Long communityId) {
        PaymentConfig cfg = loadConfig(communityId);
        Map<String, Object> data = new LinkedHashMap<>();
        boolean wechat = cfg != null && cfg.wechatChannelReady();
        boolean alipay = cfg != null && cfg.alipayChannelReady();
        // 本地联调：未配密钥也可拉起支付（模拟入账）
        if (!wechat && !alipay && aggregatorPayClient.isMock() && payProperties.isMockAllowWithoutOnboarding()) {
            wechat = true;
            alipay = true;
            data.put("mockBypassOnboarding", true);
        }
        data.put("onboardingStatus", cfg == null ? "DRAFT" : nullTo(cfg.getOnboardingStatus(), "DRAFT"));
        data.put("wechatEnabled", wechat);
        data.put("alipayEnabled", alipay);
        data.put("offlineGuideAvailable", false);
        data.put("vendor", payProperties.getVendor());
        data.put("mock", aggregatorPayClient.isMock());
        data.put("configured", cfg != null && cfg.anyOnlineReady());
        return data;
    }

    @Transactional
    public Map<String, Object> createPay(List<Long> billIds, String channelRaw) {
        PayChannel channel = PayChannel.from(channelRaw);
        if (channel == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "channel 须为 WECHAT 或 ALIPAY");
        }
        if (billIds == null || billIds.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billIds 不能为空");
        }
        AuthUser user = AuthContext.require();
        List<Bill> bills = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Long communityId = null;
        for (Long id : billIds) {
            Bill b = billingService.requireResidentBillPublic(id);
            if (!"PUBLISHED".equals(b.getStatus())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "仅已发放未缴账单可支付，账单#" + id);
            }
            if (communityId == null) {
                communityId = b.getCommunityId();
            } else if (!communityId.equals(b.getCommunityId())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "合并支付须同一小区");
            }
            total = total.add(b.getTotalAmount());
            bills.add(b);
        }
        PaymentConfig cfg = loadConfig(communityId);
        String subMchId = resolveSubMch(cfg, channel, communityId);
        assertChannelAllowed(cfg, channel, subMchId);

        String outTradeNo = "P" + communityId + "T" + System.currentTimeMillis()
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String subject = bills.size() == 1
                ? ("物业费账单#" + bills.get(0).getId())
                : ("物业费合并支付" + bills.size() + "笔");
        String payerId = resolvePayerId(channel, user.getUserId());

        Map<String, Object> client = aggregatorPayClient.createOrder(
                channel, subMchId, outTradeNo, total, subject, payerId);

        LocalDateTime now = LocalDateTime.now();
        PayOrder order = new PayOrder();
        order.setCommunityId(communityId);
        order.setOutTradeNo(outTradeNo);
        order.setChannel(channel.name());
        order.setSubMchId(subMchId);
        order.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        order.setStatus("CREATED");
        order.setBillIds(bills.stream().map(b -> String.valueOf(b.getId())).collect(Collectors.joining(",")));
        order.setPayerUserId(user.getUserId());
        try {
            order.setClientPayload(objectMapper.writeValueAsString(client));
        } catch (Exception e) {
            order.setClientPayload(String.valueOf(client.get("prepayId")));
        }
        order.setRefundedAmount(BigDecimal.ZERO);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        payOrderMapper.insert(order);

        Object prepayId = client.get("prepayId");
        if (prepayId != null && bills.size() == 1) {
            Bill b = bills.get(0);
            b.setWechatTransactionId(String.valueOf(prepayId));
            b.setUpdatedAt(now);
            billMapper.updateById(b);
        }

        Map<String, Object> data = new LinkedHashMap<>(client);
        data.put("outTradeNo", outTradeNo);
        data.put("payOrderId", order.getId());
        data.put("billIds", billIds);
        data.put("totalAmount", order.getAmount());
        return data;
    }

    @Transactional
    public Map<String, Object> handleNotify(String channelRaw, Map<String, Object> body) {
        PayChannel channel = PayChannel.from(channelRaw);
        if (channel == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "未知渠道");
        }
        Map<String, Object> verified = aggregatorPayClient.verifyNotify(channel, body);
        String outTradeNo = str(verified.get("outTradeNo"));
        String thirdTradeNo = str(verified.get("thirdTradeNo"));
        if (thirdTradeNo == null && body != null) {
            thirdTradeNo = str(body.get("prepayId"));
        }

        PayOrder order = null;
        if (outTradeNo != null) {
            order = payOrderMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                    .eq(PayOrder::getOutTradeNo, outTradeNo)
                    .last("LIMIT 1"));
        }
        if (order == null && thirdTradeNo != null) {
            Bill b = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getWechatTransactionId, thirdTradeNo)
                    .last("LIMIT 1"));
            if (b != null) {
                order = payOrderMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                        .eq(PayOrder::getCommunityId, b.getCommunityId())
                        .like(PayOrder::getBillIds, String.valueOf(b.getId()))
                        .eq(PayOrder::getStatus, "CREATED")
                        .orderByDesc(PayOrder::getId)
                        .last("LIMIT 1"));
                if (order == null) {
                    return settleLegacyBill(b, channel, thirdTradeNo);
                }
                if (outTradeNo == null) {
                    outTradeNo = order.getOutTradeNo();
                }
            }
        }
        if (order == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "未找到对应支付单，请先发起支付");
        }
        if (!channel.name().equals(order.getChannel())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "回调渠道与支付单不一致");
        }
        if ("SUCCESS".equals(order.getStatus()) || "REFUNDED".equals(order.getStatus())
                || "PARTIAL_REFUND".equals(order.getStatus())) {
            return Map.of("idempotent", true, "outTradeNo", order.getOutTradeNo(),
                    "billIds", parseBillIds(order.getBillIds()));
        }
        if (!"CREATED".equals(order.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "支付单状态不可确认：" + order.getStatus());
        }

        Object amtObj = verified.get("amountYuan");
        if (amtObj != null) {
            BigDecimal notified = new BigDecimal(String.valueOf(amtObj)).setScale(2, RoundingMode.HALF_UP);
            if (notified.compareTo(order.getAmount()) != 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "回调金额与支付单不一致");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        order.setStatus("SUCCESS");
        order.setThirdTradeNo(thirdTradeNo);
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        try {
            order.setNotifyRaw(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {
            order.setNotifyRaw(String.valueOf(body));
        }
        payOrderMapper.updateById(order);

        String recordChannel = channel.recordChannel();
        List<Long> ids = parseBillIds(order.getBillIds());
        for (Long billId : ids) {
            Bill b = billMapper.selectById(billId);
            if (b == null || "PAID".equals(b.getStatus())) {
                continue;
            }
            billingService.settleOnlinePaid(b, recordChannel, order.getPayerUserId(),
                    order.getOutTradeNo(), thirdTradeNo,
                    channel.name() + "子商户收款" + (aggregatorPayClient.isMock() ? "(MOCK)" : ""));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("outTradeNo", order.getOutTradeNo());
        resp.put("billIds", ids);
        resp.put("mock", aggregatorPayClient.isMock());
        return resp;
    }

    @Transactional
    public Map<String, Object> refundForBill(Bill bill, BigDecimal amount, String reason) {
        if (bill == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of("skipped", true, "reason", "金额无效");
        }
        PayChannel channel = PayChannel.from(bill.getPayChannel());
        if (channel == null) {
            return Map.of("skipped", true, "reason", "非线上渠道");
        }
        PayOrder order = findSuccessOrderForBill(bill.getId());
        if (order == null) {
            return Map.of("skipped", true, "reason", "无成功支付单，仅做账务冲红");
        }
        BigDecimal already = order.getRefundedAmount() == null ? BigDecimal.ZERO : order.getRefundedAmount();
        BigDecimal remain = order.getAmount().subtract(already);
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of("skipped", true, "reason", "支付单已退尽");
        }
        BigDecimal refund = amount.min(remain);
        Map<String, Object> r = aggregatorPayClient.refund(
                channel, order.getSubMchId(), order.getOutTradeNo(),
                order.getThirdTradeNo(), refund, reason);
        LocalDateTime now = LocalDateTime.now();
        order.setRefundedAmount(already.add(refund));
        order.setStatus(order.getRefundedAmount().compareTo(order.getAmount()) >= 0 ? "REFUNDED" : "PARTIAL_REFUND");
        order.setUpdatedAt(now);
        payOrderMapper.updateById(order);
        Map<String, Object> data = new LinkedHashMap<>(r);
        data.put("payOrderId", order.getId());
        data.put("refundedAmount", refund);
        return data;
    }

    public PayOrder findSuccessOrderForBill(Long billId) {
        if (billId == null) {
            return null;
        }
        List<PayOrder> list = payOrderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .in(PayOrder::getStatus, List.of("SUCCESS", "PARTIAL_REFUND", "REFUNDED"))
                .like(PayOrder::getBillIds, String.valueOf(billId))
                .orderByDesc(PayOrder::getId)
                .last("LIMIT 20"));
        for (PayOrder o : list) {
            if (parseBillIds(o.getBillIds()).contains(billId)) {
                return o;
            }
        }
        return null;
    }

    private Map<String, Object> settleLegacyBill(Bill b, PayChannel channel, String thirdTradeNo) {
        if ("PAID".equals(b.getStatus())) {
            return Map.of("idempotent", true, "billId", b.getId());
        }
        if (!"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账单状态不可确认支付");
        }
        billingService.settleOnlinePaid(b, channel.recordChannel(),
                AuthContext.get() == null ? null : AuthContext.get().getUserId(),
                null, thirdTradeNo,
                channel.name() + "商户支付(MOCK-legacy)");
        return Map.of("ok", true, "billId", b.getId(), "mock", true, "legacy", true);
    }

    private void assertChannelAllowed(PaymentConfig cfg, PayChannel channel, String subMchId) {
        if (aggregatorPayClient.isMock() && payProperties.isMockAllowWithoutOnboarding()) {
            return;
        }
        if (channel == PayChannel.WECHAT) {
            if (cfg == null || !cfg.wechatChannelReady()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "本小区未配齐微信支付（商户号+APIv3密钥+证书序列号+商户私钥）");
            }
        } else if (cfg == null || !cfg.alipayChannelReady()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "本小区未配齐支付宝（APPID+应用私钥+支付宝公钥）");
        }
        if (!notBlank(subMchId)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "商户号未配置");
        }
    }

    private String resolveSubMch(PaymentConfig cfg, PayChannel channel, Long communityId) {
        if (cfg != null) {
            if (channel == PayChannel.WECHAT && notBlank(cfg.getWechatSubMchId())) {
                return cfg.getWechatSubMchId().trim();
            }
            if (channel == PayChannel.ALIPAY && notBlank(cfg.getAlipaySmid())) {
                return cfg.getAlipaySmid().trim();
            }
        }
        if (aggregatorPayClient.isMock() && payProperties.isMockAllowWithoutOnboarding()) {
            return "MOCK_SUB_" + channel.name() + "_" + communityId;
        }
        return null;
    }

    private String resolvePayerId(PayChannel channel, Long userId) {
        if (channel != PayChannel.WECHAT || userId == null) {
            return null;
        }
        UserWechat bind = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getUserId, userId)
                .eq(UserWechat::getAppId, wxProperties.getAppId())
                .last("LIMIT 1"));
        return bind == null ? null : bind.getOpenid();
    }

    private PaymentConfig loadConfig(Long communityId) {
        if (communityId == null) {
            return null;
        }
        return paymentConfigMapper.selectOne(new LambdaQueryWrapper<PaymentConfig>()
                .eq(PaymentConfig::getCommunityId, communityId)
                .last("LIMIT 1"));
    }

    static List<Long> parseBillIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String p : raw.split(",")) {
            try {
                ids.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
