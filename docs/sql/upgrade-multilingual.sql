-- =============================================================================
-- 增量升级脚本：支持多文件类型（.java 之外的配置文件、脚本、前端源码）
--
-- 适用对象：**本功能上线前就已建好库**的环境。新装环境直接跑 schema.sql 即可，
-- 那份脚本里的种子数据已经包含本节的全部内容，不要重复执行本文件。
--
-- 为什么不能用 schema.sql 代替：那份脚本开头会 DROP 并重建全部表，在跑的库跑它等于清库。
--
-- 为什么必须单独执行：规则配置页只能修改「已存在」的阈值行
-- （RuleConfigService.updateThreshold 对未知 key 直接返回 false），
-- 缺了这一行，STYLE.LINE_TOO_LONG 的长度上限就不会出现在阈值参数表里、无法在页面上调整。
-- 不执行也能跑：RuleThresholds 的 Java 默认值与本节一致，只是页面不可见、不可改。
--
-- 执行一次即可（语句本身幂等，重复执行无副作用）：
--   docker exec -i mysql mysql -uroot -p123456 code_quality < docs/sql/upgrade-multilingual.sql
-- =============================================================================

-- 新增：单行最大长度
INSERT INTO `rule_threshold` (`config_key`, `config_value`, `value_type`, `description`)
VALUES ('maxLineLength', '120', 'INT', '单行最大长度，超出触发 STYLE.LINE_TOO_LONG')
ON DUPLICATE KEY UPDATE
    `description` = VALUES(`description`);

-- 放开文件类型后，构建产物会被一并捞进扫描范围，忽略列表必须同步补齐
UPDATE `rule_threshold`
SET `config_value` = '**/target/**,**/generated/**,**/*.pb.java,**/node_modules/**,**/dist/**,**/build/**,**/*.min.js,**/*.min.css,**/package-lock.json,**/yarn.lock,**/pnpm-lock.yaml'
WHERE `config_key` = 'ignoredFilePatterns'
  AND `config_value` = '**/target/**,**/generated/**,**/*.pb.java';
