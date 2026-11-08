-- ============================================================================
-- D26 知识文档登记与导入表 DDL（MySQL 8.x，InnoDB，utf8mb4）
-- 可安全重复执行：CREATE TABLE IF NOT EXISTS，不删除或覆盖任何已有数据。
-- 库：ai_db
-- 职责：knowledge_document 存文档元数据、导入状态与 contentHash；
--       不存整段文件内容、不做 Chunk/Embedding（D26 边界）。
-- 字段体系对齐 D25 KnowledgeDocument 字典；source 对应字典中的 sourceSystem。
-- 回滚见技术文档：DROP TABLE IF EXISTS knowledge_document;
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`    VARCHAR(64)  NOT NULL COMMENT '文档业务标识，服务端生成，UUID 去横线',
    `source`         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '文档来源（对应 D25 sourceSystem）',
    `title`          VARCHAR(200) NOT NULL DEFAULT '' COMMENT '文档标题，安全截断',
    `version`        INT          NOT NULL DEFAULT 1 COMMENT '文档版本，从 1 开始',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '导入状态：PENDING/PROCESSING/COMPLETED/FAILED',
    `content_hash`   VARCHAR(64)  NULL COMMENT '原始字节 SHA-256，重复导入识别；登记态无文件时为 NULL',
    `content_length` BIGINT       NOT NULL DEFAULT 0 COMMENT '原始字节长度',
    `error_code`     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '失败错误码',
    `error_message`  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要，不含堆栈与密钥',
    `created_at`     DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`     DATETIME     NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_id` (`document_id`),
    UNIQUE KEY `uk_content_hash` (`content_hash`),
    KEY `idx_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识文档表（D26 登记与导入）';
