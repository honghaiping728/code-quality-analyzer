package com.cq.scan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cq.common.model.Repo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 仓库 Mapper
 * <p>
 * 在线仓库扫描时按 URL 登记仓库，并回填 last_commit_id（增量扫描基线）与 last_scan_time。
 */
@Mapper
public interface RepoMapper extends BaseMapper<Repo> {
}
