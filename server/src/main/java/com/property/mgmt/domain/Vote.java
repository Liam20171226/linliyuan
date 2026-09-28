package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vote")
public class Vote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private String title;
    private String background;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
    private Long createdBy;
    private String creatorRole;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
