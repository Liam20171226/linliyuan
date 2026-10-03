package com.property.mgmt.integration.pay;

import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.config.PayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 默认实现：app.pay.mock=true 或未配齐聚合密钥时使用。
 * 正式接持牌聚合 SDK 后，可新增 RealAggregatorPayClient 并以 @Primary / 条件装配切换。
 */
@Component
@RequiredArgsConstructor
public class MockAggregatorPayClient implements AggregatorPayClient {

    private final PayProperties pay;

    @Override
    public boolean isMock() {
        return pay.isMock() || !pay.aggregatorReady();
    }

    @Override
    public Map<String, Object> createOrder(PayChannel channel, String subMchId, String outTradeNo,
                                           BigDecimal amountYuan, String subject, String payerId) {
        if (amountYuan == null || amountYuan.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "支付金额无效");
        }
        if (subMchId == null || subMchId.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "子商户号未配置");
        }
        if (!isMock()) {
            throw BizException.of(ErrorCodes.SERVER,
                    "聚合正式下单尚未接入 SDK：请保持 app.pay.mock=true 联调，或实现 RealAggregatorPayClient");
        }
        String prepayId = "MOCK_" + channel.name() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mock", true);
        data.put("vendor", pay.getVendor());
        data.put("channel", channel.name());
        data.put("subMchId", subMchId);
        data.put("outTradeNo", outTradeNo);
        data.put("amount", amountYuan.setScale(2, RoundingMode.HALF_UP));
        data.put("subject", subject);
        data.put("prepayId", prepayId);
        data.put("tradeNo", prepayId);
        data.put("message", "MOCK：可调 /pay/notify/" + channel.name().toLowerCase() + " 模拟回调");
        if (channel == PayChannel.WECHAT) {
            data.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
            data.put("nonceStr", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            data.put("package", "prepay_id=" + prepayId);
            data.put("signType", "RSA");
            data.put("paySign", "MOCK_SIGN");
        } else {
            data.put("orderStr", "MOCK_ALIPAY_ORDER_" + outTradeNo);
        }
        return data;
    }

    @Override
    public Map<String, Object> verifyNotify(PayChannel channel, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "回调体为空");
        }
        String outTradeNo = str(body.get("outTradeNo"));
        if (outTradeNo == null) {
            outTradeNo = str(body.get("out_trade_no"));
        }
        String prepayId = str(body.get("prepayId"));
        if (prepayId == null) {
            prepayId = str(body.get("tradeNo"));
        }
        if (prepayId == null) {
            prepayId = str(body.get("thirdTradeNo"));
        }
        if (outTradeNo == null && prepayId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "outTradeNo 或 prepayId 必填");
        }
        if (!isMock()) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "正式回调须完成聚合验签后方可入账");
        }
        boolean mockToken = prepayId != null && prepayId.startsWith("MOCK_");
        if (!mockToken && !pay.isMock()) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非法回调");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("outTradeNo", outTradeNo);
        ok.put("thirdTradeNo", prepayId != null ? prepayId : ("MOCK_TX_" + outTradeNo));
        Object amt = body.get("amount");
        if (amt == null) {
            amt = body.get("total_amount");
        }
        ok.put("amountYuan", amt);
        ok.put("mock", true);
        return ok;
    }

    @Override
    public Map<String, Object> refund(PayChannel channel, String subMchId, String outTradeNo,
                                      String thirdTradeNo, BigDecimal refundYuan, String reason) {
        if (!isMock() && !pay.aggregatorReady()) {
            throw BizException.of(ErrorCodes.SERVER, "聚合退款未配置");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mock", isMock());
        data.put("channel", channel.name());
        data.put("outTradeNo", outTradeNo);
        data.put("thirdTradeNo", thirdTradeNo);
        data.put("refundAmount", refundYuan);
        data.put("refundNo", "RF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        data.put("ok", true);
        return data;
    }

    @Override
    public Map<String, Object> fetchDailySummary(PayChannel channel, String subMchId, String yyyyMmDd) {
        // MOCK：由对账服务用本地 pay_order 汇总，此处返回空表示「以平台为准回放」
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mock", true);
        data.put("channel", channel.name());
        data.put("subMchId", subMchId);
        data.put("date", yyyyMmDd);
        data.put("useLocalReplay", true);
        return data;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
