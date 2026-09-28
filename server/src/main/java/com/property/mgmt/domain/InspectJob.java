package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inspect_job")
public class InspectJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long planId;
    private Integer seqNo;
    private String status;
    private Long assigneeUserId;
    private LocalDateTime claimedAt;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
