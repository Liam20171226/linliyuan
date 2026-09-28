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
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
