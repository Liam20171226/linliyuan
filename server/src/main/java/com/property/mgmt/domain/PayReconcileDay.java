package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pay_reconcile_day")
public class PayReconcileDay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private LocalDate reconcileDate;
    /** WECHAT / ALIPAY / ALL */
    private String channel;
    private Integer platformCount;
    private BigDecimal platformAmount;
    private Integer channelCount;
    private BigDecimal channelAmount;
    private BigDecimal diffAmount;
    /** MATCHED / MISMATCH / PENDING */
    private String status;
    private String detailJson;
    private Long createdBy;
    private LocalDateTime createdAt;
}
