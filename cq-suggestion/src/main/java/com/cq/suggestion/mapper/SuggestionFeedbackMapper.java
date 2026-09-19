package com.cq.suggestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.SuggestionFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采纳反馈 Mapper
 */
@Mapper
public interface SuggestionFeedbackMapper extends BaseMapper<SuggestionFeedback> {
}
