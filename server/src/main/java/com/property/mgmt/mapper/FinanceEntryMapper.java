package com.property.mgmt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.property.mgmt.domain.FinanceEntry;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FinanceEntryMapper extends BaseMapper<FinanceEntry> {
}
