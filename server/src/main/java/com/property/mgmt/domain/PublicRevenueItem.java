package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("public_revenue_item")
public class PublicRevenueItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String title;
    private BigDecimal amount;
    private String occurMonth;
    private String remark;
    private String status;
    private Long submittedBy;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private String rejectReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
