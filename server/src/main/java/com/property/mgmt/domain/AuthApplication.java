package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("auth_application")
public class AuthApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private Long applicantUserId;
    private String applicantName;
    private String applicantMobile;
    private String applicantIdCardNo;
    private Long houseTypeId;
    private String applyRole;
    private String applyMessage;
    private String status;
    private String source;
    private String rejectReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private Long resultOccupantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
