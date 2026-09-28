package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("room_occupant")
public class RoomOccupant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long communityId;
    private Long roomId;
    private Long userId;
    private String residentRole;
    private String status;
    private String source;
    private LocalDateTime approvedAt;
    private Long approvedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
