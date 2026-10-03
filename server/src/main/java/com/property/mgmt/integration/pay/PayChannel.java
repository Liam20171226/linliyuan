package com.property.mgmt.integration.pay;

public enum PayChannel {
    WECHAT,
    ALIPAY;

    public static PayChannel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase();
        if ("WECHAT".equals(s) || "WECHAT_MCH".equals(s) || "WX".equals(s)) {
            return WECHAT;
        }
        if ("ALIPAY".equals(s) || "ALIPAY_MCH".equals(s) || "ZFB".equals(s)) {
            return ALIPAY;
        }
        return null;
    }

    public String recordChannel() {
        return this == WECHAT ? "WECHAT_MCH" : "ALIPAY_MCH";
    }
}
