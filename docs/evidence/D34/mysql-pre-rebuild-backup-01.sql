-- D34 MySQL 表重建前备份（自动生成，可用于还原旧表与数据）
-- 生成时间: 2026-11-17T18:21:47.461124

-- ===== 旧表 DDL: knowledge_document =====
CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` varchar(64) NOT NULL,
  `source_system` varchar(128) NOT NULL,
  `title` varchar(300) NOT NULL,
  `version` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `file_name` varchar(255) DEFAULT NULL,
  `content_type` varchar(128) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `content_hash` char(64) DEFAULT NULL,
  `error_code` varchar(64) NOT NULL DEFAULT '',
  `error_message` varchar(500) NOT NULL DEFAULT '',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_id` (`document_id`),
  UNIQUE KEY `uk_document_content_hash` (`document_id`,`content_hash`),
  KEY `idx_knowledge_doc_status` (`status`,`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=455 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='D26 知识文档元数据与导入状态';
-- ===== 数据行: knowledge_document =====
INSERT INTO `knowledge_document` (`id`,`document_id`,`source_system`,`title`,`version`,`status`,`file_name`,`content_type`,`file_size`,`content_hash`,`error_code`,`error_message`,`created_at`,`updated_at`) VALUES (453,'d57fc9f7a28e4ae87f20d558720fe6a0','D33-RAG-IT','D33端到端液压机故障手册','1','COMPLETED',NULL,NULL,343,'0c85374a23a6a4bccc9e2fba9231daf5f8da17f61b4a3c7974b5e3f0db45e918','','','2026-11-17T00:37:15.375097','2026-11-17T00:37:15.477991');
INSERT INTO `knowledge_document` (`id`,`document_id`,`source_system`,`title`,`version`,`status`,`file_name`,`content_type`,`file_size`,`content_hash`,`error_code`,`error_message`,`created_at`,`updated_at`) VALUES (454,'21cd26c1bfe172866ea8767426161c8c','D33-DEMO','D33演示液压机故障手册','1','COMPLETED',NULL,NULL,344,'c72120d8af952a8f6188bc55eca7f03f3783d534ad304428f67146c40f012d91','','','2026-11-17T00:38:50.600011','2026-11-17T00:38:50.630809');

-- ===== 旧表 DDL: knowledge_document_page =====
CREATE TABLE `knowledge_document_page` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` varchar(64) NOT NULL,
  `page_number` int NOT NULL,
  `content` longtext NOT NULL,
  `character_count` int NOT NULL,
  `blank_page` tinyint(1) NOT NULL,
  `parser_name` varchar(64) NOT NULL,
  `parser_version` varchar(32) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_page` (`document_id`,`page_number`),
  KEY `idx_knowledge_document_page_doc` (`document_id`,`page_number`)
) ENGINE=InnoDB AUTO_INCREMENT=323 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='D27 PDF 物理页面文本与来源定位';
-- ===== 数据行: knowledge_document_page =====
INSERT INTO `knowledge_document_page` (`id`,`document_id`,`page_number`,`content`,`character_count`,`blank_page`,`parser_name`,`parser_version`,`updated_at`) VALUES (321,'d57fc9f7a28e4ae87f20d558720fe6a0',1,'液压机压力不足故障排查手册。
第一条：液压机压力不足时，应先检查液压泵输出压力与溢流阀设定值。
第二条：油温过高会导致油液粘度下降，进而造成系统压力波动，应检查冷却系统。
第三条：系统压力波动也可能是吸油管路堵塞或油箱液位过低造成。
',117,0,'BasicDocumentParser','D33','2026-11-17T00:37:15.375097');
INSERT INTO `knowledge_document_page` (`id`,`document_id`,`page_number`,`content`,`character_count`,`blank_page`,`parser_name`,`parser_version`,`updated_at`) VALUES (322,'21cd26c1bfe172866ea8767426161c8c',1,'液压机压力不足故障排查手册。
第一条：液压机压力不足时，应先检查液压泵输出压力与溢流阀设定值。
第二条：油温过高会导致油液粘度下降，进而造成系统压力波动，应检查冷却系统。
第三条：系统压力波动也可能是吸油管路堵塞或油箱液位过低造成。
',118,0,'BasicDocumentParser','D33','2026-11-17T00:38:50.600011');
