package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inspect_visit")
public class InspectVisit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long jobId;
    private Long spotId;
    private Long userId;
    private String note;
    private Long photoAttachmentId;
    private LocalDateTime scannedAt;
    private LocalDateTime createdAt;
}
