package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prepaid_plan_item")
public class PrepaidPlanItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Long communityId;
    private Long roomId;
    private String billMonth;
    private String feeCategory;
    private BigDecimal listAmount;
    /** 按原价占比分摊的实收，计入该账期公开收入 */
    private BigDecimal cashAmount;
    private LocalDateTime createdAt;
}
