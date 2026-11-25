-- ===========================================================================
-- D42 P0（KP2）：治理面 MySQL DDL——ai_db 五表，全部幂等（IF NOT EXISTS）
-- 依据：docs\temp_docs\D41\ddl\d41_schema.sql 逐列同构改编（表名 d41_→d42_，D40/D41 列口径）。
-- 必要性实扫：D42 骨架未建任何 d42_ MySQL 表（SHOW TABLES LIKE 'd42%' = 0）；
--   删除管线（sync 标记事务）与 reconcile（syncMapper/tableExtractMapper.selectList）
--   均硬依赖 d42_document_sync / d42_table_extract → seeding 前必须建表。
-- 执行 harness：tools/D42DdlSeed.java（×2 重跑零报错零数据变化 = 幂等判据）
-- ===========================================================================

CREATE TABLE IF NOT EXISTS d42_document_sync (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  document_id   VARCHAR(64)   NOT NULL COMMENT '文档业务 ID（d42- 前缀=夹具域）',
  operation     ENUM('DELETE','UPDATE_CLEANUP') NOT NULL COMMENT '操作级同步类型',
  version       INT           NOT NULL DEFAULT 0 COMMENT '0=全版本(DELETE)；>0=目标旧版本(UPDATE_CLEANUP)',
  sync_status   ENUM('PENDING','SYNCED','FAILED') NOT NULL COMMENT '三态（KP4 冻结）',
  reason_code   VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '6 原因码或空串；耗尽=RETRY_EXHAUSTED',
  attempt_count INT           NOT NULL DEFAULT 0 COMMENT '失败累计；手动 retry 对耗尽行重置 0',
  next_retry_at DATETIME      NULL COMMENT '到期口径=now+retryBackoffMs；NULL=不等待',
  payload_hash  CHAR(64)      NOT NULL DEFAULT '' COMMENT '排序 chunkId 集 SHA-256（操作快照指纹）',
  created_at    DATETIME      NOT NULL,
  updated_at    DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_d42_doc_op_version (document_id, operation, version),
  KEY idx_d42_sync_status_retry (sync_status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D42 操作级同步状态机（D40 口径，表名 d42_）';

CREATE TABLE IF NOT EXISTS d42_admin_audit (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  actor_user_id      VARCHAR(64)  NOT NULL COMMENT '服务端解析 principal（无凭据=ANONYMOUS）',
  tenant_id          VARCHAR(64)  NOT NULL,
  role               VARCHAR(16)  NOT NULL COMMENT '服务端权威 role 快照',
  action             VARCHAR(64)  NOT NULL COMMENT '端点动作名',
  target_document_id VARCHAR(64)  NOT NULL DEFAULT '',
  version            INT          NOT NULL DEFAULT 0,
  result             VARCHAR(16)  NOT NULL COMMENT 'ALLOWED/DENIED 双分支均留痕',
  reason_code        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '拒绝原因码或空',
  request_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'MDC requestId 链路贯通',
  created_at         DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_d42_audit_actor_time (actor_user_id, created_at),
  KEY idx_d42_audit_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D42 特权操作审计（仅 access.enabled=true 写入；H9 零凭据明文）';

CREATE TABLE IF NOT EXISTS d42_auth_principal (
  token_sha256  CHAR(64)      NOT NULL COMMENT 'Bearer 令牌 SHA-256 小写 hex（DB 不存明文，H9）',
  user_id       VARCHAR(64)   NOT NULL,
  tenant_id     VARCHAR(64)   NOT NULL COMMENT '租户（硬隔离边界）',
  dept_id       VARCHAR(64)   NOT NULL,
  device_scopes JSON          NOT NULL COMMENT '可见设备数组；[]=无专属设备仅看 * 文档',
  role          ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '服务端角色（唯一权威）',
  enabled       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '禁用位（0=fail-closed 拒绝）',
  expires_at    DATETIME      NULL COMMENT '过期时刻（NULL=不过期）',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token_sha256),
  KEY idx_d42_principal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D42 凭据表（结构同构 d39/d40/d41_auth_principal；表隔离，前序资产只读；R01 access 关闭不使用）';

CREATE TABLE IF NOT EXISTS d42_table_extract (
  id                     BIGINT      NOT NULL AUTO_INCREMENT,
  document_id            VARCHAR(64) NOT NULL,
  version                INT         NOT NULL COMMENT '记录绑定文档版本（D7）',
  page_number            INT         NOT NULL COMMENT '物理页 1-based（表头行所在页）',
  table_index            INT         NOT NULL COMMENT '页内表序号 1-based（续表独立记录）',
  header_snapshot_json   JSON        NOT NULL COMMENT '表头列名数组（跨页续表匹配键）',
  row_count              INT         NOT NULL DEFAULT 0 COMMENT '数据行数（不含表头）',
  col_count              INT         NOT NULL DEFAULT 0,
  source_global_start    INT         NULL COMMENT '规范全文 UTF-16 起始（含）；与 Chunk.globalStart 同口径',
  source_global_end      INT         NULL COMMENT '规范全文 UTF-16 结束（不含）',
  source_line_start      INT         NULL COMMENT '页文本行号 1-based（表格文本首行）',
  source_line_end        INT         NULL COMMENT '页文本行号 1-based（表格文本末行）',
  detect_flags           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔退化/异常码（KP4 码表）',
  confidence             VARCHAR(16) NOT NULL DEFAULT 'HIGH' COMMENT 'HIGH/MEDIUM/LOW（无 OCR 时图片表 N/A 不入表）',
  continuation_of_table_id BIGINT    NULL COMMENT '跨页续表指向前表 id（KP4：新记录+回链，非合并）',
  status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ORPHANED（TABLE_ORPHAN 对账标记）',
  created_at             DATETIME    NOT NULL,
  updated_at             DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_d42_doc_ver_page_table (document_id, version, page_number, table_index),
  KEY idx_d42_table_doc_ver (document_id, version),
  KEY idx_d42_table_global (document_id, version, source_global_start, source_global_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D42 表格抽取记录（D41 同构；删除/旧版清理随 VectorIndexStateTx 事务；reconcile 表轨检测依赖）';

CREATE TABLE IF NOT EXISTS d42_table_row (
  id                 BIGINT      NOT NULL AUTO_INCREMENT,
  table_id           BIGINT      NOT NULL COMMENT 'd42_table_extract.id（弱外键，随表记录同事务删）',
  row_index          INT         NOT NULL COMMENT '数据行序 1-based（表头不占位）',
  cells_json         JSON        NOT NULL COMMENT '类型化数组 [{value,unit,type,isNull}] 按表头列序',
  source_line_number INT         NULL COMMENT '来源行号（页文本 1-based）',
  record_hash        CHAR(64)    NOT NULL COMMENT '规范化 cells 序列化 SHA-256（幂等/对账）',
  created_at         DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_d42_table_row (table_id, row_index),
  KEY idx_d42_row_hash (record_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D42 表格数据行（D41 同构；缺失单元格 null 占位不串列；全 null 行不落库）';
