package com.cq.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用日志 Mapper
 */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
