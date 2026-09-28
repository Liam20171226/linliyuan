package com.property.mgmt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.property.mgmt.domain.BillLine;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillLineMapper extends BaseMapper<BillLine> {
}
