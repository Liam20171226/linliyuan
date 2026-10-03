package com.property.mgmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多物业收款：各小区直连微信、支付宝官方商户（不经第三方聚合）。
 * 小区密钥在收款配置页填写；平台级 mock/notify 走环境变量。
 */
@Data
@ConfigurationProperties(prefix = "app.pay")
public class PayProperties {

    /**
     * OFFICIAL=微信+支付宝官方商户；AGGREGATOR=持牌聚合（备用）。
     */
    private String vendor = "OFFICIAL";

    /** true=本地 MOCK 下单/回调；false=走微信/支付宝正式接口（需配齐密钥） */
    private boolean mock = true;

    /** 平台侧备用标识（官方直连时可空） */
    private String platformMchId = "";

    private String appId = "";

    private String apiKey = "";

    /** 统一异步通知基址，实际路径为 /pay/notify/wechat|/pay/notify/alipay */
    private String notifyBaseUrl = "";

    /** MOCK 下是否允许未开通商户仍拉起线上支付（仅本地联调） */
    private boolean mockAllowWithoutOnboarding = true;

    public boolean aggregatorReady() {
        return !mock
                && notifyBaseUrl != null && !notifyBaseUrl.isBlank();
    }
}
