package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_config")
public class PaymentConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String guideText;
    private Long qrAttachmentId;
    /** 1=启用预缴，0=关闭；与商户/线下指引并列单独配置 */
    private Integer prepaidEnabled;
    private String prepaidGuideText;
    /** 1=开通微信线上收款 */
    private Integer wechatEnabled;
    /** 1=开通支付宝线上收款 */
    private Integer alipayEnabled;
    /** 微信特约/官方商户号 */
    private String wechatSubMchId;
    /** 支付宝应用 APPID / 商户 PID */
    private String alipaySmid;
    /** 微信 App / 小程序 appId（H5/App 拉起支付用） */
    private String wechatAppId;
    /** 微信 APIv3 密钥 */
    private String wechatApiV3Key;
    /** 微信商户证书序列号 */
    private String wechatMchSerialNo;
    /** 微信商户私钥 PEM */
    private String wechatPrivateKeyPem;
    /** 支付宝应用私钥 */
    private String alipayPrivateKey;
    /** 支付宝公钥 */
    private String alipayPublicKey;
    /** DRAFT / PENDING / ACTIVE / REJECTED */
    private String onboardingStatus;
    private String onboardingRemark;
    /** 聚合进件 H5/链接（运营代进件时可空） */
    private String onboardingUrl;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean wechatChannelReady() {
        return wechatEnabled != null && wechatEnabled == 1
                && notBlank(wechatSubMchId)
                && notBlank(wechatApiV3Key)
                && notBlank(wechatMchSerialNo)
                && notBlank(wechatPrivateKeyPem);
    }

    public boolean alipayChannelReady() {
        return alipayEnabled != null && alipayEnabled == 1
                && notBlank(alipaySmid)
                && notBlank(alipayPrivateKey)
                && notBlank(alipayPublicKey);
    }

    public boolean anyOnlineReady() {
        return wechatChannelReady() || alipayChannelReady();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
