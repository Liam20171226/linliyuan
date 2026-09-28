package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prepaid_ledger")
public class PrepaidLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private Long accountId;
    /** RECHARGE / OFFSET / REFUND / ADJUST */
    private String entryType;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Long billId;
    private String payChannel;
    private Long operatorUserId;
    private String remark;
    private LocalDateTime createdAt;
}
