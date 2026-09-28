package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("floor")
public class Floor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long buildingId;
    private Long unitId;
    private String name;
    private Integer floorNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
