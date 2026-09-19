package com.cq.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.IssueStat;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问题统计快照 Mapper
 */
@Mapper
public interface IssueStatMapper extends BaseMapper<IssueStat> {
}
