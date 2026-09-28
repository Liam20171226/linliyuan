package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("finance_entry")
public class FinanceEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String entryType;
    private BigDecimal amount;
    private LocalDate occurDate;
    private String category;
    private String title;
    private String remark;
    private String status;
    private Long createdBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
