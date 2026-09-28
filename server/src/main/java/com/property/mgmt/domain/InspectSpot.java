package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inspect_spot")
public class InspectSpot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long buildingId;
    private Long unitId;
    private Long floorId;
    private String name;
    private String categories;
    private String reqSafety;
    private String reqCleaning;
    private String reqFacility;
    private String reqLandscape;
    private String qrToken;
    private Integer sortNo;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
