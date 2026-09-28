package com.property.mgmt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.property.mgmt.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
