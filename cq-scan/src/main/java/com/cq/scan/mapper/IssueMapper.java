package com.cq.scan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.Issue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问题记录 Mapper
 */
@Mapper
public interface IssueMapper extends BaseMapper<Issue> {
}
