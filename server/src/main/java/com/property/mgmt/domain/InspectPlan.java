package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inspect_plan")
public class InspectPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String title;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String freqUnit;
    private Integer freqTimes;
    private Integer ordered;
    private String requirementNote;
    private String executorRoles;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
