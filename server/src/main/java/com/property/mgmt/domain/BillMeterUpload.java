package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("bill_meter_upload")
public class BillMeterUpload {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private String billMonth;
    private String feeCategory;
    private BigDecimal meterStart;
    private BigDecimal meterEnd;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private Long batchId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
