package com.property.mgmt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.property.mgmt.domain.Vote;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VoteMapper extends BaseMapper<Vote> {
}
