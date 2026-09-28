package com.property.mgmt.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("vote_option")
public class VoteOption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long voteId;
    private String optionText;
    private Integer sortNo;
}
