-- ============================================================================
-- D38 one_ 轨（阿里百炼远程 Embedding）向量索引状态表 + Chunk 关联表 DDL
-- MySQL 8.x，InnoDB，utf8mb4；可安全重复执行（CREATE TABLE IF NOT EXISTS）。
-- 库：ai_db
-- 与 local 轨关系（任务书 H2/H3 双轨隔离契约）：
--   one_document_vector_index / one_document_chunk_index 为 one_ 轨独立新表，
--   结构复制 local 轨 document_vector_index / document_chunk_index，
--   新增 vector_space_id 列绑定向量空间（bailian|qwen3.7-text-embedding|remote-v1|1024）；
--   local 轨两表与共享文档表（knowledge_document/document_page_content）零改动。
-- 向量本体：PG 表 one_d38_vector_store vector(1024)/HNSW/COSINE，
--   由 onePgVectorStore initialize-schema 自动创建（维度=P0 探测冻结值）。
-- chunkId 设计：复用 StableChunkIdGenerator（UUID v5 不含模型身份），
--   同一逻辑 chunk 两轨同 UUID，uk_chunk_id 按表独立无冲突，支持跨轨按 chunk 对齐对比；
--   同 UUID 不代表向量可互换，跨空间复用必须被兼容性检查拒绝。
-- 回滚：DROP TABLE IF EXISTS one_document_chunk_index, one_document_vector_index;
-- ============================================================================

-- ---------------------------------------------------------------------------
-- one_ 轨文档级向量索引状态表：一个文档版本一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `one_document_vector_index` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`     VARCHAR(64)  NOT NULL COMMENT '文档业务标识，关联共享 knowledge_document.document_id',
    `version`         INT          NOT NULL COMMENT '文档版本，从 1 开始',
    `content_hash`    CHAR(64)     NOT NULL COMMENT '索引时文档内容指纹 SHA-256',
    `strategy`        VARCHAR(16)  NOT NULL COMMENT '切分策略：FIXED_SIZE/STRUCTURE',
    `chunk_count`     INT          NOT NULL DEFAULT 0 COMMENT '该版本 Chunk 总数',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED',
    `stage`           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '最后完成阶段：SPLIT/EMBED_WRITE/SWITCH/CLEANUP',
    `error_code`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '失败错误码',
    `error_message`   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
    `attempt_count`   INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数，含首次',
    `next_retry_at`   DATETIME     NULL COMMENT '下次计划重试时间，NULL 表示不等待',
    `vector_space_id` VARCHAR(160) NOT NULL COMMENT 'one_ 轨向量空间标识（provider|model|version|dims），跨空间复用拒绝依据',
    `created_at`      DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_version` (`document_id`, `version`),
    KEY `idx_status_retry` (`status`, `next_retry_at`),
    KEY `idx_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'one_ 轨文档向量索引状态表（D38 阿里百炼双轨，结构对齐 local 轨 + vector_space_id）';

-- ---------------------------------------------------------------------------
-- one_ 轨 Chunk 级关联表：一个 Chunk 一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `one_document_chunk_index` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`        VARCHAR(64) NOT NULL COMMENT '所属文档业务标识',
    `version`            INT         NOT NULL COMMENT '所属文档版本',
    `strategy`           VARCHAR(16) NOT NULL COMMENT '切分策略',
    `chunk_id`           CHAR(36)    NOT NULL COMMENT '稳定 Chunk 标识（UUID v5，与 local 轨同逻辑 chunk 同 ID，按表独立唯一）',
    `chunk_index`        INT         NOT NULL COMMENT '文档版本与策略内的 Chunk 顺序，从 0 开始',
    `content`            MEDIUMTEXT  NOT NULL COMMENT 'Chunk 文本快照（Outbox 载荷，供缺失向量重建）',
    `chunk_content_hash` CHAR(64)    NOT NULL COMMENT 'Chunk 文本内容指纹 SHA-256',
    `char_length`        INT         NOT NULL COMMENT 'Chunk 文本 UTF-16 长度（字符，非 Token）',
    `global_start`       INT         NOT NULL COMMENT '规范全文起始偏移，UTF-16',
    `global_end`         INT         NOT NULL COMMENT '规范全文结束偏移，UTF-16',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'INDEXED' COMMENT '关联状态：INDEXED/FAILED',
    `vector_space_id`    VARCHAR(160) NOT NULL COMMENT 'one_ 轨向量空间标识',
    `created_at`         DATETIME    NOT NULL COMMENT '创建时间',
    `updated_at`         DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    UNIQUE KEY `uk_doc_ver_strat_idx` (`document_id`, `version`, `strategy`, `chunk_index`),
    KEY `idx_document` (`document_id`, `version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'one_ 轨文档 Chunk 关联表（D38 阿里百炼双轨，结构对齐 local 轨 + vector_space_id）';