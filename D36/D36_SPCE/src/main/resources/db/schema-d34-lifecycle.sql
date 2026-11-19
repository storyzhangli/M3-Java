-- ============================================================================
-- D34 生命周期状态字段迁移（MySQL 8.x，InnoDB，utf8mb4）
-- 非破坏性：仅新增列，不删除/覆盖任何历史数据；默认 ACTIVE，存量行自动生效。
-- 说明：MySQL 8.0 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS，
--       因此先查 information_schema，仅在列缺失时执行（由迁移程序/人工判断后执行）。
-- 本脚本不自动执行；执行前确认目标库为 ai_db。
-- ============================================================================

-- 1) 检查列是否已存在（结果为 0 表示缺失，需要执行下面的 ALTER）：
SELECT COUNT(*) AS lifecycle_column_present
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'ai_db'
  AND TABLE_NAME = 'knowledge_document'
  AND COLUMN_NAME = 'lifecycle_status';

-- 2) 仅当上面计数为 0 时执行（人工或受控迁移程序据此判断）：
ALTER TABLE `knowledge_document`
    ADD COLUMN `lifecycle_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '生命周期：DRAFT/ACTIVE/DEPRECATED' AFTER `status`;

-- 回滚（确需时，人工执行）：
-- ALTER TABLE `knowledge_document` DROP COLUMN `lifecycle_status`;
