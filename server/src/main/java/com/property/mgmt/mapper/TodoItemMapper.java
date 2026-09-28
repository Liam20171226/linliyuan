package com.property.mgmt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.property.mgmt.domain.TodoItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TodoItemMapper extends BaseMapper<TodoItem> {
}
