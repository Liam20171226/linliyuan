package com.property.mgmt.integration;

import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.config.WxProperties;
import com.property.mgmt.domain.Bill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 JSAPI。默认 mock；正式环境需配齐商户参数（见 {@link WxProperties.Pay}）。
 * 完整 V3 签名/证书校验上线前在服务器配好密钥后可继续接官方 SDK。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPayApi {

    private final WxProperties wx;

    public boolean isMock() {
        return wx.getPay() == null || wx.getPay().isMock() || !wx.payReady();
    }

    public Map<String, Object> createJsapiOrder(Bill bill, String openid) {
        if (bill.getTotalAmount() == null || bill.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账单金额无效");
        }
        if (wx.getPay() != null && !wx.getPay().isMock() && !wx.payReady()) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "微信支付未配齐：请设置 app.wx.pay.mock=false 且填写 mchId/serial/apiV3Key/privateKeyPem/notifyUrl");
        }
        if (isMock()) {
            String prepayId = "MOCK_PREPAY_" + bill.getId() + "_" + System.currentTimeMillis();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mock", true);
            data.put("prepayId", prepayId);
            data.put("billId", bill.getId());
            data.put("amount", bill.getTotalAmount());
            data.put("message", "MOCK：小程序可继续调 /pay/wechat/notify 模拟回调");
            return data;
        }
        if (openid == null || openid.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "支付需要用户 openid，请先完成微信登录绑定");
        }
        // 正式下单占位：接入 wechatpay-java 后在此调用 /v3/pay/transactions/jsapi
        // 当前抛出明确错误，避免半成品签名上线
        log.warn("WeChat Pay real mode requested but JSAPI client not fully wired; mchId={}",
                wx.getPay().getMchId());
        throw BizException.of(ErrorCodes.SERVER,
                "商户参数已配置，请安装并接入微信支付 API v3 SDK 完成 JSAPI 下单（见 doc/05-上线联调清单.md）");
    }

    public int yuanToFen(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    public String outTradeNo(Long billId) {
        return "B" + billId + "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
