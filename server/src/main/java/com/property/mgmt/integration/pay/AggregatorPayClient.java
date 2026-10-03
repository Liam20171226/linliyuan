package com.property.mgmt.integration.pay;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 持牌聚合 / 服务商下单抽象。真实 SDK 接入时替换 {@link MockAggregatorPayClient} 或新增实现。
 * 下单时必须带物业子商户号，清算进子商户。
 */
public interface AggregatorPayClient {

    boolean isMock();

    /**
     * @param channel   WECHAT / ALIPAY
     * @param subMchId  小区子商户号
     * @param outTradeNo 平台支付单号
     * @param amountYuan 金额（元）
     * @param subject   商品描述
     * @param payerId   微信 openid 或支付宝 buyer_id（可空，MOCK 可空）
     * @return 前端拉起支付所需参数（含 mock/prepayId/tradeNo 等）
     */
    Map<String, Object> createOrder(PayChannel channel, String subMchId, String outTradeNo,
                                    BigDecimal amountYuan, String subject, String payerId);

    /**
     * 校验异步通知；MOCK 模式下校验 outTradeNo/amount 一致性即可。
     * @return 标准化结果：outTradeNo, thirdTradeNo, amountYuan, success
     */
    Map<String, Object> verifyNotify(PayChannel channel, Map<String, Object> body);

    /**
     * 原路退款（冲红/重复支付）。MOCK 直接成功。
     */
    Map<String, Object> refund(PayChannel channel, String subMchId, String outTradeNo,
                               String thirdTradeNo, BigDecimal refundYuan, String reason);

    /**
     * 拉取某日渠道侧成功交易汇总（对账用）。MOCK 用本地 pay_order 回放。
     */
    Map<String, Object> fetchDailySummary(PayChannel channel, String subMchId, String yyyyMmDd);
}
