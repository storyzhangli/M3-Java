-- one_document_vector_index
CREATE TABLE `one_document_vector_index` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '文档业务标识，关联共享 knowledge_document.document_id',
  `version` int NOT NULL COMMENT '文档版本，从 1 开始',
  `content_hash` char(64) NOT NULL COMMENT '索引时文档内容指纹 SHA-256',
  `strategy` varchar(16) NOT NULL COMMENT '切分策略：FIXED_SIZE/STRUCTURE',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '该版本 Chunk 总数',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED',
  `stage` varchar(32) NOT NULL DEFAULT '' COMMENT '最后完成阶段：SPLIT/EMBED_WRITE/SWITCH/CLEANUP',
  `error_code` varchar(64) NOT NULL DEFAULT '' COMMENT '失败错误码',
  `error_message` varchar(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '已尝试次数，含首次',
  `next_retry_at` datetime DEFAULT NULL COMMENT '下次计划重试时间，NULL 表示不等待',
  `vector_space_id` varchar(160) NOT NULL COMMENT 'one_ 轨向量空间标识（provider|model|version|dims），跨空间复用拒绝依据',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_version` (`document_id`,`version`),
  KEY `idx_status_retry` (`status`,`next_retry_at`),
  KEY `idx_status_updated` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='one_ 轨文档向量索引状态表（D38 阿里百炼双轨，结构对齐 local 轨 + vector_space_id）';

-- one_document_chunk_index
CREATE TABLE `one_document_chunk_index` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '所属文档业务标识',
  `version` int NOT NULL COMMENT '所属文档版本',
  `strategy` varchar(16) NOT NULL COMMENT '切分策略',
  `chunk_id` char(36) NOT NULL COMMENT '稳定 Chunk 标识（UUID v5，与 local 轨同逻辑 chunk 同 ID，按表独立唯一）',
  `chunk_index` int NOT NULL COMMENT '文档版本与策略内的 Chunk 顺序，从 0 开始',
  `content` mediumtext NOT NULL COMMENT 'Chunk 文本快照（Outbox 载荷，供缺失向量重建）',
  `chunk_content_hash` char(64) NOT NULL COMMENT 'Chunk 文本内容指纹 SHA-256',
  `char_length` int NOT NULL COMMENT 'Chunk 文本 UTF-16 长度（字符，非 Token）',
  `global_start` int NOT NULL COMMENT '规范全文起始偏移，UTF-16',
  `global_end` int NOT NULL COMMENT '规范全文结束偏移，UTF-16',
  `status` varchar(16) NOT NULL DEFAULT 'INDEXED' COMMENT '关联状态：INDEXED/FAILED',
  `vector_space_id` varchar(160) NOT NULL COMMENT 'one_ 轨向量空间标识',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_id` (`chunk_id`),
  UNIQUE KEY `uk_doc_ver_strat_idx` (`document_id`,`version`,`strategy`,`chunk_index`),
  KEY `idx_document` (`document_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='one_ 轨文档 Chunk 关联表（D38 阿里百炼双轨，结构对齐 local 轨 + vector_space_id）';

