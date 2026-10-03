package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pay_order")
public class PayOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String outTradeNo;
    /** WECHAT / ALIPAY */
    private String channel;
    private String subMchId;
    private BigDecimal amount;
    /** CREATED / SUCCESS / CLOSED / REFUNDED / PARTIAL_REFUND */
    private String status;
    private String thirdTradeNo;
    /** 逗号分隔 billId */
    private String billIds;
    private Long payerUserId;
    private String clientPayload;
    private String notifyRaw;
    private LocalDateTime paidAt;
    private BigDecimal refundedAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
