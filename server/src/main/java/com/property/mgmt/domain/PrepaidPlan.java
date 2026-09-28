package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预缴协议（方案 A）：约定冲抵月份 + 费项；实付可含折扣；出账跳过覆盖项；公开按账期结转实收。
 */
@Data
@TableName("prepaid_plan")
public class PrepaidPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    /** 覆盖费项原价合计 */
    private BigDecimal listAmount;
    /** 业主实付 */
    private BigDecimal cashAmount;
    /** list - cash，仅台账，不进公开收入 */
    private BigDecimal discountAmount;
    /** DRAFT / ACTIVE / VOID */
    private String status;
    private String payChannel;
    private String remark;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
