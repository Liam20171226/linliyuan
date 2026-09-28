package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_record")
public class PaymentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long billId;
    private Long roomId;
    private Long payerUserId;
    private BigDecimal amount;
    private String payChannel;
    private Long confirmedBy;
    private LocalDateTime paidAt;
    private String remark;
    /** 收款当时账单费项快照（中文名，顿号拼接）；账单日后改明细也不变 */
    private String feeTypeLabel;
    /** 收款当时费项编码，逗号分隔 */
    private String feeCategories;
    private LocalDateTime createdAt;
}
