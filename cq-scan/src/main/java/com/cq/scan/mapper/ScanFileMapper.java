package com.cq.scan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.ScanFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 扫描文件明细 Mapper
 */
@Mapper
public interface ScanFileMapper extends BaseMapper<ScanFile> {
}
