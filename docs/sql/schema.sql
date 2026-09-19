-- =============================================================================
-- 基于大语言模型的智能代码质量分析与自动修复建议系统 —— 数据库 Schema
-- MySQL 8.0+ / InnoDB / utf8mb4
--
-- 执行方式：
--   docker exec -i mysql mysql -uroot -p123456 < docs/sql/schema.sql
-- 或在容器内：
--   docker exec mysql mysql -uroot -p123456 -e "source /path/to/schema.sql"
--
-- 设计说明：
--   1. issue 表上的唯一键 (task_id, rule_id, file_path, line, line_hash) 实现天然去重，
--      同一扫描任务内同一条规则在同一位置只保留一条记录。
--   2. 抑制/误报匹配使用 line_hash（源码行内容的 hash）而非裸行号 —— 行号会随上方代码
--      编辑整体位移，仅按行号匹配会导致抑制在第一次提交后就失效或误抑制。
--   3. 被标记为误报的问题保留记录并置 status = FALSE_POSITIVE，不物理删除：
--      「误报反馈自学习」特性依赖这批数据做阈值调优。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `code_quality`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `code_quality`;

-- 按依赖倒序清理，便于重复执行
DROP TABLE IF EXISTS `llm_call_log`;
DROP TABLE IF EXISTS `suggestion_feedback`;
DROP TABLE IF EXISTS `fix_suggestion`;
DROP TABLE IF EXISTS `ignore_entry`;
DROP TABLE IF EXISTS `rule_threshold`;
DROP TABLE IF EXISTS `rule_config`;
DROP TABLE IF EXISTS `issue_stat`;
DROP TABLE IF EXISTS `scan_report`;
DROP TABLE IF EXISTS `issue`;
DROP TABLE IF EXISTS `file_snapshot`;
DROP TABLE IF EXISTS `scan_file`;
DROP TABLE IF EXISTS `scan_task`;
DROP TABLE IF EXISTS `repo`;
DROP TABLE IF EXISTS `sys_user`;

-- -----------------------------------------------------------------------------
-- 1. sys_user —— 用户（cq-admin 权限管理）
-- -----------------------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)  NOT NULL                COMMENT '登录名',
    `password`    VARCHAR(128) NOT NULL                COMMENT '口令（加盐哈希，禁止明文）',
    `display_name` VARCHAR(64)                         COMMENT '显示名',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'ADMIN | USER',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户';

-- -----------------------------------------------------------------------------
-- 2. repo —— 仓库
-- -----------------------------------------------------------------------------
CREATE TABLE `repo` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `name`           VARCHAR(128) NOT NULL                COMMENT '仓库显示名',
    `url`            VARCHAR(512) NOT NULL                COMMENT '远程仓库地址',
    `default_branch` VARCHAR(128) NOT NULL DEFAULT 'main' COMMENT '默认分支',
    `local_path`     VARCHAR(512)                         COMMENT '本地克隆路径',
    `username`       VARCHAR(64)                          COMMENT '拉取凭证-用户名',
    `credential`     VARCHAR(512)                         COMMENT '拉取凭证-token（加密存储）',
    `last_commit_id` VARCHAR(64)                          COMMENT '上次扫描到的提交，增量扫描基线',
    `last_scan_time` DATETIME                             COMMENT '上次扫描时间',
    `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_repo_url` (`url`),
    KEY `idx_repo_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '代码仓库';

-- -----------------------------------------------------------------------------
-- 3. scan_task —— 扫描任务
-- -----------------------------------------------------------------------------
CREATE TABLE `scan_task` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `repo_id`       BIGINT                                COMMENT '所属仓库，本地目录扫描时为 NULL',
    `target_path`   VARCHAR(512) NOT NULL                 COMMENT '扫描目标：仓库本地路径或目录',
    `mode`          VARCHAR(16)  NOT NULL DEFAULT 'FULL'  COMMENT 'FULL 全量 | INCREMENTAL 增量',
    `base_commit`   VARCHAR(64)                           COMMENT '增量扫描的基线提交',
    `head_commit`   VARCHAR(64)                           COMMENT '增量扫描的目标提交',
    `trigger_type`  VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL | WEBHOOK | SCHEDULE',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | RUNNING | SUCCESS | FAILED | CANCELLED',
    `file_count`    INT          NOT NULL DEFAULT 0       COMMENT '扫描文件数',
    `issue_count`   INT          NOT NULL DEFAULT 0       COMMENT '发现问题数',
    `start_time`    DATETIME                              COMMENT '开始时间',
    `end_time`      DATETIME                              COMMENT '结束时间',
    `duration_ms`   BIGINT                                COMMENT '耗时（毫秒）',
    `error_message` TEXT                                  COMMENT '失败原因',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_status` (`status`),
    KEY `idx_task_repo` (`repo_id`, `create_time`),
    CONSTRAINT `fk_task_repo` FOREIGN KEY (`repo_id`) REFERENCES `repo` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '扫描任务';

-- -----------------------------------------------------------------------------
-- 4. scan_file —— 任务的文件明细
-- -----------------------------------------------------------------------------
CREATE TABLE `scan_file` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`     BIGINT       NOT NULL,
    `file_path`   VARCHAR(512) NOT NULL              COMMENT '相对路径',
    `line_count`  INT          NOT NULL DEFAULT 0    COMMENT '代码行数',
    `issue_count` INT          NOT NULL DEFAULT 0    COMMENT '该文件问题数',
    `parsed`      TINYINT(1)   NOT NULL DEFAULT 1    COMMENT '是否解析成功',
    `parse_error` TEXT                               COMMENT '解析错误信息',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scanfile_task_path` (`task_id`, `file_path`),
    KEY `idx_scanfile_task` (`task_id`),
    CONSTRAINT `fk_scanfile_task` FOREIGN KEY (`task_id`) REFERENCES `scan_task` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '扫描文件明细';

-- -----------------------------------------------------------------------------
-- 5. file_snapshot —— 文件快照（增量扫描与趋势分析）
-- -----------------------------------------------------------------------------
CREATE TABLE `file_snapshot` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `repo_id`     BIGINT                                COMMENT '所属仓库',
    `commit_id`   VARCHAR(64)  NOT NULL                 COMMENT '提交 ID',
    `file_path`   VARCHAR(512) NOT NULL                 COMMENT '相对路径',
    `content_hash` CHAR(64)     NOT NULL                COMMENT '文件内容 SHA-256，用于判断是否变更',
    `line_count`  INT          NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_commit_path` (`commit_id`, `file_path`),
    KEY `idx_snapshot_repo` (`repo_id`, `commit_id`),
    CONSTRAINT `fk_snapshot_repo` FOREIGN KEY (`repo_id`) REFERENCES `repo` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件快照';

-- -----------------------------------------------------------------------------
-- 6. issue —— 问题记录
-- -----------------------------------------------------------------------------
CREATE TABLE `issue` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`     BIGINT        NOT NULL                 COMMENT '所属扫描任务',
    `repo_id`     BIGINT                                 COMMENT '所属仓库（冗余，便于按仓库查询）',
    `commit_id`   VARCHAR(64)                            COMMENT '提交 ID',
    `rule_id`     VARCHAR(64)   NOT NULL                 COMMENT '规则 ID，如 BUG.EMPTY_CATCH',
    `file_path`   VARCHAR(512)  NOT NULL                 COMMENT '文件相对路径',
    `line`        INT           NOT NULL DEFAULT 0       COMMENT '行号',
    `line_hash`   CHAR(64)                               COMMENT '问题所在源码行的 SHA-256，用于跨版本稳定匹配',
    `type`        VARCHAR(16)   NOT NULL                 COMMENT 'BUG | SECURITY | PERFORMANCE | STYLE',
    `severity`    VARCHAR(16)   NOT NULL                 COMMENT 'BLOCKER | CRITICAL | MAJOR | MINOR',
    `source`      VARCHAR(16)   NOT NULL DEFAULT 'RULE'  COMMENT 'RULE | LLM | FUSED',
    `confidence`  DECIMAL(3, 2) NOT NULL DEFAULT 1.00    COMMENT '置信度 0.00-1.00',
    `message`     VARCHAR(512)  NOT NULL                 COMMENT '问题描述',
    `cause`       TEXT                                   COMMENT '成因解释',
    `suggestion`  TEXT                                   COMMENT '修复建议',
    `diff`        TEXT                                   COMMENT '修复代码 Diff',
    `code_snippet` TEXT                                  COMMENT '问题所在代码片段',
    `status`      VARCHAR(16)   NOT NULL DEFAULT 'OPEN'  COMMENT 'OPEN | CONFIRMED | FALSE_POSITIVE | FIXED',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_issue_dedup` (`task_id`, `rule_id`, `file_path`, `line`, `line_hash`),
    KEY `idx_issue_task` (`task_id`),
    KEY `idx_issue_type` (`type`, `severity`),
    KEY `idx_issue_status` (`status`),
    KEY `idx_issue_rule` (`rule_id`),
    KEY `idx_issue_file` (`file_path`),
    CONSTRAINT `fk_issue_task` FOREIGN KEY (`task_id`) REFERENCES `scan_task` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_issue_repo` FOREIGN KEY (`repo_id`) REFERENCES `repo` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问题记录';

-- -----------------------------------------------------------------------------
-- 7. fix_suggestion —— 修复建议
-- -----------------------------------------------------------------------------
CREATE TABLE `fix_suggestion` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `issue_id`      BIGINT        NOT NULL                 COMMENT '关联问题',
    `original_code` TEXT                                   COMMENT '修改前代码',
    `fixed_code`    TEXT                                   COMMENT '修改后代码',
    `diff`          TEXT                                   COMMENT '统一 diff 文本',
    `explanation`   TEXT                                   COMMENT '修复说明',
    `source`        VARCHAR(16)   NOT NULL DEFAULT 'LLM'   COMMENT 'TEMPLATE | LLM',
    `confidence`    DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    `verified`      TINYINT(1)    NOT NULL DEFAULT 0       COMMENT '是否通过编译/测试验证',
    `verify_output` TEXT                                   COMMENT '验证输出',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_suggestion_issue` (`issue_id`),
    KEY `idx_suggestion_verified` (`verified`),
    CONSTRAINT `fk_suggestion_issue` FOREIGN KEY (`issue_id`) REFERENCES `issue` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '修复建议';

-- -----------------------------------------------------------------------------
-- 8. suggestion_feedback —— 采纳反馈（阈值调优数据源）
-- -----------------------------------------------------------------------------
CREATE TABLE `suggestion_feedback` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `suggestion_id` BIGINT                                COMMENT '关联修复建议',
    `issue_id`      BIGINT       NOT NULL                 COMMENT '关联问题',
    `rule_id`       VARCHAR(64)  NOT NULL                 COMMENT '冗余规则 ID，便于按规则统计',
    `action`        VARCHAR(16)  NOT NULL                 COMMENT 'ACCEPT 采纳 | REJECT 拒绝 | FALSE_POSITIVE 误报',
    `comment`       VARCHAR(512)                          COMMENT '反馈备注',
    `user_id`       BIGINT                                COMMENT '反馈人',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_feedback_issue` (`issue_id`),
    KEY `idx_feedback_rule` (`rule_id`, `action`),
    CONSTRAINT `fk_feedback_issue` FOREIGN KEY (`issue_id`) REFERENCES `issue` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_feedback_suggestion` FOREIGN KEY (`suggestion_id`) REFERENCES `fix_suggestion` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '修复建议采纳反馈';

-- -----------------------------------------------------------------------------
-- 9. report —— 审查报告（任务级汇总）
-- -----------------------------------------------------------------------------
CREATE TABLE `scan_report` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`          BIGINT        NOT NULL,
    `repo_id`          BIGINT                                 COMMENT '所属仓库',
    `total_files`      INT           NOT NULL DEFAULT 0       COMMENT '扫描文件数',
    `total_lines`      BIGINT        NOT NULL DEFAULT 0       COMMENT '代码总行数',
    `total_issues`     INT           NOT NULL DEFAULT 0       COMMENT '问题总数',
    `bug_count`        INT           NOT NULL DEFAULT 0,
    `security_count`   INT           NOT NULL DEFAULT 0,
    `performance_count` INT          NOT NULL DEFAULT 0,
    `style_count`      INT           NOT NULL DEFAULT 0,
    `blocker_count`    INT           NOT NULL DEFAULT 0,
    `critical_count`   INT           NOT NULL DEFAULT 0,
    `major_count`      INT           NOT NULL DEFAULT 0,
    `minor_count`      INT           NOT NULL DEFAULT 0,
    `issue_density`    DECIMAL(10, 4) NOT NULL DEFAULT 0      COMMENT '问题密度：问题数 / 千行代码',
    `fixed_count`      INT           NOT NULL DEFAULT 0       COMMENT '已修复数',
    `false_positive_count` INT       NOT NULL DEFAULT 0       COMMENT '误报数',
    `fix_rate`         DECIMAL(5, 4)  NOT NULL DEFAULT 0      COMMENT '修复率 0-1',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_task` (`task_id`),
    KEY `idx_report_repo` (`repo_id`, `create_time`),
    CONSTRAINT `fk_report_task` FOREIGN KEY (`task_id`) REFERENCES `scan_task` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_report_repo` FOREIGN KEY (`repo_id`) REFERENCES `repo` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审查报告';

-- -----------------------------------------------------------------------------
-- 10. issue_stat —— 统计快照（趋势图数据源）
-- -----------------------------------------------------------------------------
CREATE TABLE `issue_stat` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `repo_id`     BIGINT                                 COMMENT '按仓库聚合，NULL 表示全局',
    `task_id`     BIGINT                                 COMMENT '来源任务',
    `stat_date`   DATE         NOT NULL                  COMMENT '统计日期',
    `dimension`   VARCHAR(16)  NOT NULL                  COMMENT 'TYPE 按问题类型 | SEVERITY 按严重级',
    `dim_value`   VARCHAR(32)  NOT NULL                  COMMENT '维度取值，如 BUG / BLOCKER',
    `issue_count` INT          NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stat_scope` (`repo_id`, `task_id`, `dimension`, `dim_value`),
    KEY `idx_stat_date` (`stat_date`),
    CONSTRAINT `fk_stat_task` FOREIGN KEY (`task_id`) REFERENCES `scan_task` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_stat_repo` FOREIGN KEY (`repo_id`) REFERENCES `repo` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问题统计快照';

-- -----------------------------------------------------------------------------
-- 11. rule_config —— 规则配置
-- -----------------------------------------------------------------------------
CREATE TABLE `rule_config` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT,
    `rule_id`     VARCHAR(64)   NOT NULL                 COMMENT '规则 ID，如 STYLE.METHOD_TOO_LONG',
    `rule_name`   VARCHAR(128)                           COMMENT '规则名称',
    `category`    VARCHAR(16)                            COMMENT 'BUG | SECURITY | PERFORMANCE | STYLE',
    `enabled`     TINYINT(1)    NOT NULL DEFAULT 1       COMMENT '是否启用',
    `severity`    VARCHAR(16)                            COMMENT '严重级覆盖，NULL 表示用规则默认值',
    `confidence`  DECIMAL(3, 2)                          COMMENT '置信度覆盖，NULL 表示用规则默认值',
    `description` VARCHAR(512)                           COMMENT '规则说明',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_id` (`rule_id`),
    KEY `idx_rule_category` (`category`),
    KEY `idx_rule_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '规则配置';

-- -----------------------------------------------------------------------------
-- 12. rule_threshold —— 阈值配置（单行 key-value，避免频繁加列）
-- -----------------------------------------------------------------------------
CREATE TABLE `rule_threshold` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `config_key`  VARCHAR(64)  NOT NULL                 COMMENT '配置键，如 methodMaxLines',
    `config_value` VARCHAR(512) NOT NULL                COMMENT '配置值',
    `value_type`  VARCHAR(16)  NOT NULL DEFAULT 'INT'   COMMENT 'INT | STRING | BOOLEAN | LIST',
    `description` VARCHAR(256)                          COMMENT '配置说明',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_threshold_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '规则阈值配置';

-- -----------------------------------------------------------------------------
-- 13. ignore_entry —— 忽略列表
-- -----------------------------------------------------------------------------
CREATE TABLE `ignore_entry` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `rule_id`     VARCHAR(64)                           COMMENT '规则 ID，NULL 表示忽略该路径下所有规则',
    `file_pattern` VARCHAR(512) NOT NULL                COMMENT '路径 glob，如 **/test/**',
    `line_hash`   CHAR(64)                              COMMENT '具体某行的内容 hash，NULL 表示整个文件',
    `reason`      VARCHAR(512)                          COMMENT '忽略原因',
    `expire_time` DATETIME                              COMMENT '过期时间，NULL 表示永久',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ignore_rule` (`rule_id`),
    KEY `idx_ignore_enabled` (`enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '忽略列表';

-- -----------------------------------------------------------------------------
-- 14. llm_call_log —— LLM 调用日志（成本可观测）
-- -----------------------------------------------------------------------------
CREATE TABLE `llm_call_log` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`           BIGINT                            COMMENT '关联扫描任务',
    `issue_id`          BIGINT                            COMMENT '关联问题',
    `scene`             VARCHAR(32)  NOT NULL             COMMENT '调用场景：CONFIRM 语义确认 | SUGGEST 修复建议',
    `model`             VARCHAR(64)                       COMMENT '模型名',
    `prompt_tokens`     INT          NOT NULL DEFAULT 0,
    `completion_tokens` INT          NOT NULL DEFAULT 0,
    `total_tokens`      INT          NOT NULL DEFAULT 0,
    `duration_ms`       BIGINT                            COMMENT '耗时（毫秒）',
    `success`           TINYINT(1)   NOT NULL DEFAULT 1,
    `error_message`     VARCHAR(512)                      COMMENT '失败原因',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_llm_task` (`task_id`),
    KEY `idx_llm_scene` (`scene`, `create_time`),
    CONSTRAINT `fk_llm_task` FOREIGN KEY (`task_id`) REFERENCES `scan_task` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'LLM 调用日志';

-- =============================================================================
-- 初始数据：规则阈值默认值
-- =============================================================================
INSERT INTO `rule_threshold` (`config_key`, `config_value`, `value_type`, `description`) VALUES
    ('methodMaxLines',              '50',    'INT',    '方法最大行数，超出触发 STYLE.METHOD_TOO_LONG'),
    ('methodMaxComplexity',         '10',    'INT',    '方法最大圈复杂度，超出触发 STYLE.COMPLEXITY_TOO_HIGH'),
    ('classMaxLines',               '500',   'INT',    '类型最大行数，超出触发 STYLE.CLASS_TOO_LONG'),
    ('methodMaxParameters',         '5',     'INT',    '方法最大参数个数，超出触发 STYLE.TOO_MANY_PARAMS'),
    ('maxNestingDepth',             '3',     'INT',    '最大嵌套深度，超出触发 STYLE.DEEP_NESTING（else if 不计入）'),
    ('minFieldNameLength',          '2',     'INT',    '字段最小名字长度'),
    ('maxFindingsPerRulePerFile',   '100',   'INT',    '单文件单规则命中数上限，防止生成代码刷屏'),
    ('constantNameAllowlist',       'log,logger,serialVersionUID', 'LIST', '常量命名规则白名单'),
    ('shortNameAllowlist',          'i,j,k,x,y,z,e,t,id',          'LIST', '短变量名白名单'),
    ('magicNumberAllowlist',        '0,1,2,-1,10,100,1000',        'LIST', '魔法数字白名单'),
    ('classNamingPattern',          '^[A-Z][A-Za-z0-9]*$',         'STRING', '类名命名规范'),
    ('methodNamingPattern',         '^[a-z][A-Za-z0-9]*$',         'STRING', '方法名命名规范'),
    ('constantNamingPattern',       '^[A-Z][A-Z0-9_]*$',           'STRING', '常量命名规范'),
    ('requireJavadocForPublicMethods', 'true', 'BOOLEAN', 'public 方法是否要求 Javadoc'),
    ('ignoredFilePatterns',         '**/target/**,**/generated/**,**/*.pb.java', 'LIST', '默认忽略的路径');

-- =============================================================================
-- 初始数据：管理员账号（口令为 bcrypt 哈希，请在首次登录后立即修改）
-- =============================================================================
INSERT INTO `sys_user` (`username`, `password`, `display_name`, `role`) VALUES
    ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', 'ADMIN');
