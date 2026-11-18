-- ============================================================================
-- D27 文档页面级提取结果 DDL（MySQL 8.x，InnoDB，utf8mb4）
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不删除或覆盖任何已有数据。
-- 库：ai_db
-- 职责：knowledge_document_page 存 PDF 页面级提取文本与来源定位（物理页码），
--       与原始文件、knowledge_document 元数据分离；不是 RAG Chunk 表。
-- 页码：page_number 为从 1 开始的物理页序号，区别于印刷页码。
-- 回滚：DROP TABLE IF EXISTS knowledge_document_page;
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_document_page` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`    VARCHAR(64)  NOT NULL COMMENT '关联 knowledge_document.document_id',
    `source`         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '文档来源（保留元数据）',
    `title`          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '文档标题（保留元数据）',
    `version`        INT          NOT NULL DEFAULT 1 COMMENT '文档版本，区分不同版本来源',
    `page_number`    INT          NOT NULL COMMENT '物理页序号，从 1 开始',
    `content`        MEDIUMTEXT   NULL COMMENT '该页提取文本',
    `content_length` INT          NOT NULL DEFAULT 0 COMMENT '该页文本字符长度',
    `blank`          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否空白页：1 空白，0 含可用文字',
    `created_at`     DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_page` (`document_id`, `page_number`),
    KEY `idx_document_id` (`document_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文档页面级提取结果表（D27 来源定位）';
