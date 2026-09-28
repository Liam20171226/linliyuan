package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vote_ballot")
public class VoteBallot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long voteId;
    private Long roomId;
    private Long voterUserId;
    private Long optionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
