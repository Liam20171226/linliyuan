package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subscribe_notify_log")
public class SubscribeNotifyLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long userId;
    private String scene;
    private String templateId;
    private String status;
    private String errorMsg;
    private String bizType;
    private Long bizId;
    private LocalDateTime createdAt;
}
