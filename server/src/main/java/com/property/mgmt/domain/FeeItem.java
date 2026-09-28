package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("fee_item")
public class FeeItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String name;
    private String feeCategory;
    private String calcType;
    private BigDecimal monthlyAmount;
    private Integer status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
