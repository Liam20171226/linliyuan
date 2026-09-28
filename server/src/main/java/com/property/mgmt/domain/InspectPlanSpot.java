package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("inspect_plan_spot")
public class InspectPlanSpot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Long spotId;
    private Integer sortNo;
}
