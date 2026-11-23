-- ===========================================================================
-- D40 P1（C03）：治理面 MySQL DDL——ai_db 三表，全部幂等（IF NOT EXISTS）
-- 列口径冻结：docs/evidence/D40/probe/sync-params.json
--   · d40_document_sync.version：NOT NULL DEFAULT 0（0=全版本 DELETE；
--     对任务书 D1 "NULL" 的偏差裁决见 kp1-kp4-adjudication.md §KP4-1——
--     MySQL 唯一键 NULL 不判重会破坏 uk 防重复事件目标）
--   · d40_admin_audit：H9 零凭据，仅服务端权威身份快照
--   · d40_auth_principal：表结构同构 d39_auth_principal（D39 资产只读不共用）
-- 执行 harness：ddl/tools/D40DdlSeed.java（×2 重跑零报错 = C03 判据）
-- ===========================================================================

CREATE TABLE IF NOT EXISTS d40_document_sync (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  document_id   VARCHAR(64)   NOT NULL COMMENT '文档业务 ID（d40- 前缀=夹具域）',
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
  UNIQUE KEY uk_doc_op_version (document_id, operation, version),
  KEY idx_sync_status_retry (sync_status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D40 操作级同步状态机：删除/旧版本清理跨系统收敛事实（与索引任务态正交）';

CREATE TABLE IF NOT EXISTS d40_admin_audit (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  actor_user_id      VARCHAR(64)  NOT NULL COMMENT '服务端解析 principal（无凭据=ANONYMOUS）',
  tenant_id          VARCHAR(64)  NOT NULL,
  role               VARCHAR(16)  NOT NULL COMMENT '服务端权威 role 快照',
  action             VARCHAR(64)  NOT NULL COMMENT '端点动作名（vector-index.delete / sync.retry …）',
  target_document_id VARCHAR(64)  NOT NULL DEFAULT '',
  version            INT          NOT NULL DEFAULT 0,
  result             VARCHAR(16)  NOT NULL COMMENT 'ALLOWED/DENIED 双分支均留痕',
  reason_code        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '拒绝原因码或空',
  request_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'MDC requestId 链路贯通',
  created_at         DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_actor_time (actor_user_id, created_at),
  KEY idx_audit_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D40 特权操作审计（仅 access.enabled=true 写入；H9 零凭据明文）';

CREATE TABLE IF NOT EXISTS d40_auth_principal (
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
  KEY idx_d40_principal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D40 凭据表（结构同构 d39_auth_principal；表隔离，D39 资产只读）';
