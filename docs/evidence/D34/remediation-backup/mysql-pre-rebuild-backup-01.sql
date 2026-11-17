-- D34 MySQL 表重建前备份（自动生成，可用于还原旧表与数据）
-- 生成时间: 2026-11-17T19:58:35.139846300

-- ===== 旧表 DDL: knowledge_document =====
CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '文档业务标识；D31 由 source+title 稳定派生或外部指定',
  `source_system` varchar(128) NOT NULL COMMENT '文档来源（领域字段 source）',
  `title` varchar(300) NOT NULL COMMENT '文档标题，安全截断',
  `version` varchar(64) NOT NULL COMMENT '文档版本，从 1 开始；沿用既有字符串列',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/PROCESSING/COMPLETED/FAILED',
  `file_name` varchar(255) DEFAULT NULL COMMENT '原始文件名',
  `content_type` varchar(128) DEFAULT NULL COMMENT '原始内容类型',
  `file_size` bigint DEFAULT NULL COMMENT '原始字节长度（领域字段 contentLength）',
  `content_hash` char(64) DEFAULT NULL COMMENT '原始字节 SHA-256；登记态为 NULL',
  `error_code` varchar(64) NOT NULL DEFAULT '' COMMENT '失败错误码',
  `error_message` varchar(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(6) NOT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_id` (`document_id`),
  UNIQUE KEY `uk_document_content_hash` (`document_id`,`content_hash`),
  KEY `idx_knowledge_doc_status` (`status`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识文档表（D26 登记与导入，D31 稳定身份）';
-- ===== 数据行: knowledge_document =====
INSERT INTO `knowledge_document` (`id`,`document_id`,`source_system`,`title`,`version`,`status`,`file_name`,`content_type`,`file_size`,`content_hash`,`error_code`,`error_message`,`created_at`,`updated_at`) VALUES (1,'d57fc9f7a28e4ae87f20d558720fe6a0','D33-RAG-IT','D33端到端液压机故障手册','1','COMPLETED',NULL,NULL,343,'0c85374a23a6a4bccc9e2fba9231daf5f8da17f61b4a3c7974b5e3f0db45e918','','','2026-11-17T18:46:09.131484','2026-11-17T18:46:09.172206');

-- ===== 旧表 DDL: knowledge_document_page =====
CREATE TABLE `knowledge_document_page` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '代理主键',
  `document_id` varchar(64) NOT NULL COMMENT '关联 knowledge_document.document_id',
  `page_number` int NOT NULL COMMENT '物理页序号，从 1 开始',
  `content` longtext NOT NULL COMMENT '该页提取文本',
  `character_count` int NOT NULL COMMENT '该页文本字符数（领域字段 contentLength）',
  `blank_page` tinyint(1) NOT NULL COMMENT '是否空白页：1 空白，0 含可用文字',
  `parser_name` varchar(64) NOT NULL COMMENT '解析器名称',
  `parser_version` varchar(32) NOT NULL COMMENT '解析器版本',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_page` (`document_id`,`page_number`),
  KEY `idx_knowledge_document_page_doc` (`document_id`,`page_number`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档页面级提取结果表（D27 来源定位）';
-- ===== 数据行: knowledge_document_page =====
INSERT INTO `knowledge_document_page` (`id`,`document_id`,`page_number`,`content`,`character_count`,`blank_page`,`parser_name`,`parser_version`,`updated_at`) VALUES (1,'d57fc9f7a28e4ae87f20d558720fe6a0',1,'液压机压力不足故障排查手册。
第一条：液压机压力不足时，应先检查液压泵输出压力与溢流阀设定值。
第二条：油温过高会导致油液粘度下降，进而造成系统压力波动，应检查冷却系统。
第三条：系统压力波动也可能是吸油管路堵塞或油箱液位过低造成。
',117,0,'BasicDocumentParser','D33','2026-11-17T18:46:09.131484');
