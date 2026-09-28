package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("fee_exemption_ledger")
public class FeeExemptionLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private String billMonth;
    private Long billId;
    private Long exemptionId;
    private String feeCategory;
    private Long feeItemId;
    private String title;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
