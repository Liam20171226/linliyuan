package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("community_application")
public class CommunityApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long applicantUserId;
    private String applicantName;
    private String applicantRole;
    private String applicantMobile;
    private String applyReason;
    private String provinceCode;
    private String provinceName;
    private String cityCode;
    private String cityName;
    private String districtCode;
    private String districtName;
    private String communityName;
    private String templateCode;
    private String status;
    private String rejectReason;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private Long communityId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
