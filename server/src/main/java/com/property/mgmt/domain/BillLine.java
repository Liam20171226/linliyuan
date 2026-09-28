package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("bill_line")
public class BillLine {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long billId;
    private String feeCategory;
    private String title;
    private BigDecimal amount;
    private String snapshotJson;
    private LocalDateTime createdAt;
}
