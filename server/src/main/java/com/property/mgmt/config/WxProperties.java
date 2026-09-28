package com.property.mgmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "app.wx")
public class WxProperties {

    /** true=开发 MOCK（code/openid、phoneCode=手机号）；false=调微信开放平台 */
    private boolean mock = true;

    private String appId = "wx-dev-appid";
    private String secret = "wx-dev-secret";

    /** 订阅消息模板：scene → templateId */
    private Map<String, String> templates = new HashMap<>();

    private Pay pay = new Pay();

    @Data
    public static class Pay {
        /** true=返回 MOCK prepay，前端可直接调 notify；false=走商户下单（需配齐商户参数） */
        private boolean mock = true;
        private String mchId = "";
        private String mchSerialNo = "";
        /** API v3 密钥（32 位） */
        private String apiV3Key = "";
        /** 商户私钥 PEM 全文或文件路径（file:...） */
        private String privateKeyPem = "";
        /** 支付结果通知 URL，须公网 HTTPS */
        private String notifyUrl = "";
        /** 小程序 appId（一般同 app.wx.app-id） */
        private String appId = "";
    }

    public String templateId(String scene) {
        if (scene == null || templates == null) {
            return null;
        }
        return templates.get(scene);
    }

    public boolean payReady() {
        Pay p = pay;
        if (p == null) {
            return false;
        }
        String app = (p.getAppId() == null || p.getAppId().isBlank()) ? appId : p.getAppId();
        return !p.isMock()
                && app != null && !app.isBlank()
                && p.getMchId() != null && !p.getMchId().isBlank()
                && p.getMchSerialNo() != null && !p.getMchSerialNo().isBlank()
                && p.getApiV3Key() != null && !p.getApiV3Key().isBlank()
                && p.getPrivateKeyPem() != null && !p.getPrivateKeyPem().isBlank()
                && p.getNotifyUrl() != null && !p.getNotifyUrl().isBlank();
    }
}
