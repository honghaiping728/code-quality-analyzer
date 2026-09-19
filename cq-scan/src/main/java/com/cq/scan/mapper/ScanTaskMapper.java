package com.cq.scan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.ScanTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 扫描任务 Mapper
 * <p>
 * 继承 {@code BaseMapper} 即获得单表 CRUD，无需 XML。
 */
@Mapper
public interface ScanTaskMapper extends BaseMapper<ScanTask> {
}
