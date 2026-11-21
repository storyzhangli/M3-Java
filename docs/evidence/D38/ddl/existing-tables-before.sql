-- document_vector_index
CREATE TABLE `document_vector_index` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '文档业务标识，关联 knowledge_document.document_id',
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
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_version` (`document_id`,`version`),
  KEY `idx_status_retry` (`status`,`next_retry_at`),
  KEY `idx_status_updated` (`status`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=139 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档向量索引状态表（D31 状态机与轻量 Outbox）';

-- document_chunk_index
CREATE TABLE `document_chunk_index` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '所属文档业务标识',
  `version` int NOT NULL COMMENT '所属文档版本',
  `strategy` varchar(16) NOT NULL COMMENT '切分策略',
  `chunk_id` char(36) NOT NULL COMMENT '稳定 Chunk 标识（UUID v5，含横线）',
  `chunk_index` int NOT NULL COMMENT '文档版本与策略内的 Chunk 顺序，从 0 开始',
  `content` mediumtext NOT NULL COMMENT 'Chunk 文本快照（Outbox 载荷）',
  `chunk_content_hash` char(64) NOT NULL COMMENT 'Chunk 文本内容指纹 SHA-256',
  `char_length` int NOT NULL COMMENT 'Chunk 文本 UTF-16 长度（字符，非 Token）',
  `global_start` int NOT NULL COMMENT '规范全文起始偏移，UTF-16',
  `global_end` int NOT NULL COMMENT '规范全文结束偏移，UTF-16',
  `status` varchar(16) NOT NULL DEFAULT 'INDEXED' COMMENT '关联状态：INDEXED/FAILED',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_id` (`chunk_id`),
  UNIQUE KEY `uk_doc_ver_strat_idx` (`document_id`,`version`,`strategy`,`chunk_index`),
  KEY `idx_document` (`document_id`,`version`)
) ENGINE=InnoDB AUTO_INCREMENT=451 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档 Chunk 关联表（D31 稳定 ID 与孤儿核对）';

-- knowledge_document
CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '文档业务标识，服务端生成，UUID 去横线',
  `source` varchar(64) NOT NULL DEFAULT '' COMMENT '文档来源（对应 D25 sourceSystem）',
  `title` varchar(200) NOT NULL DEFAULT '' COMMENT '文档标题，安全截断',
  `version` int NOT NULL DEFAULT '1' COMMENT '文档版本，从 1 开始',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '导入状态：PENDING/PROCESSING/COMPLETED/FAILED',
  `lifecycle_status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '生命周期：DRAFT/ACTIVE/DEPRECATED',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '原始字节 SHA-256，重复导入识别；登记态无文件时为 NULL',
  `content_length` bigint NOT NULL DEFAULT '0' COMMENT '原始字节长度',
  `error_code` varchar(32) NOT NULL DEFAULT '' COMMENT '失败错误码',
  `error_message` varchar(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_document_id` (`document_id`),
  UNIQUE KEY `uk_content_hash` (`content_hash`),
  KEY `idx_status_updated` (`status`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识文档表（D26 登记与导入）';

-- knowledge_document_page
CREATE TABLE `knowledge_document_page` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '关联 knowledge_document.document_id',
  `source` varchar(64) NOT NULL DEFAULT '' COMMENT '文档来源（保留元数据）',
  `title` varchar(200) NOT NULL DEFAULT '' COMMENT '文档标题（保留元数据）',
  `version` int NOT NULL DEFAULT '1' COMMENT '文档版本，区分不同版本来源',
  `page_number` int NOT NULL COMMENT '物理页序号，从 1 开始',
  `content` mediumtext COMMENT '该页提取文本',
  `content_length` int NOT NULL DEFAULT '0' COMMENT '该页文本字符长度',
  `blank` tinyint NOT NULL DEFAULT '0' COMMENT '是否空白页：1 空白，0 含可用文字',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_page` (`document_id`,`page_number`),
  KEY `idx_document_id` (`document_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档页面级提取结果表（D27 来源定位）';

