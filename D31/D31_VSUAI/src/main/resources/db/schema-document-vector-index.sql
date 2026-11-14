-- ============================================================================
-- D31 向量入库、更新与幂等：文档向量索引状态表 + Chunk 关联表 DDL
-- MySQL 8.x，InnoDB，utf8mb4；可安全重复执行（CREATE TABLE IF NOT EXISTS）。
-- 库：ai_db
-- 职责：
--   document_vector_index  文档级向量索引状态（含阶段/重试），兼作轻量 Outbox/任务表；
--   document_chunk_index   Chunk 级稳定关联（稳定 chunk_id、顺序、内容指纹），
--                          用于按文档查询、孤儿核对与补偿。
-- 与 Spring AI PgVectorStore 的关系：
--   向量本体（embedding vector(384)）仍由框架管理的 PG 表 d31_vector_store 承载；
--   本表只存业务关联与状态，不复制向量，不修改框架管理的 PG 表结构。
-- 回滚见技术文档：DROP TABLE IF EXISTS document_chunk_index, document_vector_index;
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 文档级向量索引状态表：一个文档版本一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `document_vector_index` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`   VARCHAR(64)  NOT NULL COMMENT '文档业务标识，关联 knowledge_document.document_id',
    `version`       INT          NOT NULL COMMENT '文档版本，从 1 开始',
    `content_hash`  CHAR(64)     NOT NULL COMMENT '索引时文档内容指纹 SHA-256',
    `strategy`      VARCHAR(16)  NOT NULL COMMENT '切分策略：FIXED_SIZE/STRUCTURE',
    `chunk_count`   INT          NOT NULL DEFAULT 0 COMMENT '该版本 Chunk 总数',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED',
    `stage`         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '最后完成阶段：SPLIT/EMBED_WRITE/SWITCH/CLEANUP',
    `error_code`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '失败错误码',
    `error_message` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
    `attempt_count` INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数，含首次',
    `next_retry_at` DATETIME     NULL COMMENT '下次计划重试时间，NULL 表示不等待',
    `created_at`    DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_version` (`document_id`, `version`),
    KEY `idx_status_retry` (`status`, `next_retry_at`),
    KEY `idx_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档向量索引状态表（D31 状态机与轻量 Outbox）';

-- ---------------------------------------------------------------------------
-- Chunk 级关联表：一个 Chunk 一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `document_chunk_index` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`        VARCHAR(64) NOT NULL COMMENT '所属文档业务标识',
    `version`            INT         NOT NULL COMMENT '所属文档版本',
    `strategy`           VARCHAR(16) NOT NULL COMMENT '切分策略',
    `chunk_id`           CHAR(36)    NOT NULL COMMENT '稳定 Chunk 标识（UUID v5，含横线）',
    `chunk_index`        INT         NOT NULL COMMENT '文档版本与策略内的 Chunk 顺序，从 0 开始',
    `content`            MEDIUMTEXT  NOT NULL COMMENT 'Chunk 文本快照（Outbox 载荷，供缺失向量重建）',
    `chunk_content_hash` CHAR(64)    NOT NULL COMMENT 'Chunk 文本内容指纹 SHA-256',
    `char_length`        INT         NOT NULL COMMENT 'Chunk 文本 UTF-16 长度（字符，非 Token）',
    `global_start`       INT         NOT NULL COMMENT '规范全文起始偏移，UTF-16',
    `global_end`         INT         NOT NULL COMMENT '规范全文结束偏移，UTF-16',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'INDEXED' COMMENT '关联状态：INDEXED/FAILED',
    `created_at`         DATETIME    NOT NULL COMMENT '创建时间',
    `updated_at`         DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    UNIQUE KEY `uk_doc_ver_strat_idx` (`document_id`, `version`, `strategy`, `chunk_index`),
    KEY `idx_document` (`document_id`, `version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档 Chunk 关联表（D31 稳定 ID 与孤儿核对）';
