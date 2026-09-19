package com.cq.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.IgnoreEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 忽略列表 Mapper
 */
@Mapper
public interface IgnoreEntryMapper extends BaseMapper<IgnoreEntry> {
}
