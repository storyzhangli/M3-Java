# D40-R01 #1 基线复核证据（2026-11-23）

## 1. §1.2 复核命令实测（PowerShell 7 真实执行）

| 项 | 实测值 | 任务书预期 | 判定 |
|---|---|---|---|
| HEAD | f3cf475cd735f7aa35462535b93c193faf645404 | f3cf475 | PASS |
| status --porcelain | 空（DIRTY_COUNT=0） | 干净 | PASS |
| mvn -B -f D40\pom.xml compile | 退出码 0 | 可编译 | PASS |
| PG 192.168.200.153:5432 | TcpTestSucceeded=True | 可达 | PASS |
| MySQL 127.0.0.1:3306 | True | 可达 | PASS |
| Redis 127.0.0.1:6379 | True | 可达 | PASS |
| D37 数据集 SHA-256 | 75CC8D83CFB218C404149ED718998D286FAA2E2CAFDDCFEFA394584DB4B17CFE（大小写不敏感一致） | 75cc8d83cfb2… | PASS |
| D40 main java 文件数 | 308 | 308 | PASS |
| D40 test java 文件数 | 0 | 0（D9 裁决：本轮新建单测） | PASS |
| 端口 8080 | 0 监听（空闲） | 空闲 | PASS |

## 2. 全仓 grep 核实（D1 事实点）

- 模式 `sync_status|syncStatus|SyncStatus|document_sync|admin_audit|FaultInjecting`：src/main/java **0 命中** → sync 状态机/审计表/故障注入装饰器全部为新建，符合 §0.4 D1/D6/D7 裁决。

## 3. 骨架关键代码实读结论（相对任务书 §1.1 的逐条印证）

### 3.1 DocumentVectorIndexService（vectorindex/service，669 行）
- `indexChunks`：幂等短路（COMPLETED ∧ contentHash ∧ 关联数一致）→ INDEXING/SPLIT → `lookupDocumentScope`（D39 权限三列，查询异常不吞=fail-closed）→ 稳定 ID + 分批 `vectorStore.add`（框架原生 DB Upsert）+ `chunkMapper.upsertOnNaturalKey` → 写后核对（PG 数/关联数，否则 PARTIAL/COUNT_MISMATCH）→ `stateTx.switchVersion` → `cleanupSuperseded` → COMPLETED/CLEANUP。✓与任务书一致。
- **`deleteByDocument`（316–332 行）实证现状**：读关联行 chunkId → 分批 `vectorStore.delete` → `stateTx.deleteDocumentRows`；**无 sync 落库、无 FAILED、无重试、不触碰 knowledge_document 行**；PG 失败异常直接上抛。✓ D1/D2 事实成立。
- **`cleanupSuperseded`（427–452 行）实证现状**：逐 SUPERSEDED 版本删 PG 向量 + `deleteOldVersionRows`；catch 块**仅 log.warn**（"非致命…留给核对补偿"），无任何状态落库。✓ D4 事实成立。
- `recoverStuckTasks`：PENDING/INDEXING 超 stuckMinutes → 有快照幂等重跑；attempt≥maxAttempts 跳过；无快照 → FAILED/NO_SNAPSHOT。✓
- `countPgForVersion`：`metadata->>'documentId'`/`metadata->>'version'`（json 列，D39 实测 json 非 jsonb 口径沿用）。
- `handleWriteFailure`：processed>0→PARTIAL，否则 FAILED，EMBED_WRITE_FAILED + nextRetryAt 退避。

### 3.2 VectorIndexStateTx（3 个 MySQL 本地事务方法）
- `switchVersion`：旧 COMPLETED→SUPERSEDED、当前→COMPLETED；**不更新 knowledge_document.version**（✓ D3 漂移根因实证：本方法只操作 indexMapper，无 documentMapper 依赖，需追加式注入）。
- `deleteDocumentRows`：chunkMapper.deleteByDocumentId + indexMapper.delete(eq document_id)。
- `deleteOldVersionRows`：chunkMapper.deleteByDocumentVersion。
- 构造依赖仅 indexMapper/chunkMapper——P2 需追加 KnowledgeDocumentMapper + sync 写入（同事务），改动为追加式。

### 3.3 VectorReconciliationService
- 现有 4 类：orphanVectors（PG 有 ∧ 无有效关联/任务非活动）、missingVectors、countMismatches、stuckTasks；dryRun 只报告；非 dryRun `applyCompensation`（删孤儿 `deleteVectorsByIds` + 快照重建 `indexChunks` + `recoverStuckTasks`）——修复动作已全走管线。✓ P4 追加式扩展点明确。
- `loadPgRows`：`SELECT id, metadata::text FROM "public"."d40_vector_store"`（表名走 PgVectorStoreProperties）。
- 无 VERSION_MISMATCH / SYNC_STUCK 判定（✓ D5：需新增）。
- `isActive` = COMPLETED/INDEXING/PARTIAL/PENDING；SUPERSEDED/FAILED 非活动→其向量判孤儿（DRIFT_STALE_VERSION 将作为 orphan 子集显式标类）。

### 3.4 ReconciliationReportView
- 既有字段 dryRun/pgTotalRows/orphanVectors/missingVectors/stuckTasks/countMismatches/actionsTaken + Issue(key,documentId,version,detail)。P4 追加 versionMismatches/syncStuck/driftSummary，既有字段不动。

### 3.5 VectorIndexController（写面现状=未门禁，✓ D6）
- 写面 5 端点：POST /index、POST /documents/{id}/index、DELETE /documents/{id}、POST /reconcile、POST /recover——**均无 requireAdmin**（类注释仍"无鉴权"）。
- status/chunks 已有 checkReadable（ObjectProvider<AccessPolicyService>，disabled 跳过）——P3 追加 ADMIN 门禁复用同一 provider 形态。

### 3.6 DocumentController
- register/import **未门禁**（P3 追加）；get **无 checkReadable**（注意：任务书 §1.1 称 get 有 checkReadable——实测 get/list 均无门禁，仅 pages/page/chunks/comparison 有 checkReadable；get 按 DOC_SCOPE 分级留 KP1 裁决，属 D39 已冻结行为，本轮不改读面）；pages/page/chunks/comparison checkReadable 不动。

### 3.7 access 包（D39 已交付件在 D40 的形态）
- AccessPolicyService：`@ConditionalOnProperty(app.access.enabled=true)`；`requireAdmin(context)` 仅认 `context.admin()`（服务端 role，非请求声明）；deny-code 4030。✓ P3 门禁直接复用。
- AuthContext record：userId/tenantId/deptId/deviceScopes/role，`admin()`=role==ADMIN。
- AuthPrincipalRepository：表 **d40_auth_principal**（MySQL ai_db），只 SELECT，SHA-256 哈希，fail-closed。
- AccessProperties：enabled=false 默认、wildcardSentinel="*"、publicPaths=/error。

### 3.8 CitationVerificationService（D8 交叉验证路径）
- `classify` 顺序：doc==null→CITATION_TARGET_DELETED；chunkId 空或不在 chunkOwners→CITATION_TARGET_DELETED；归属 null/blank 或不一致→CITATION_OWNERSHIP_MISMATCH；lifecycle 非 effective→EVIDENCE_NOT_EFFECTIVE；否则 NONE。
- **tombstone 语义实证**：knowledge_document 行保留（DEPRECATED）+ document_chunk_index 行已清 → 走 **chunkId 不在 chunkOwners** 分支 → CITATION_TARGET_DELETED（非 doc-null 分支）——与 D8 预期一致，C17 可断言。
- EVIDENCE_NOT_EFFECTIVE 为既有分支：若 chunk 行未清但 lifecycle=DEPRECATED → 该分支命中（删除闭环中 chunk 行先清，故正常删除命中 TARGET_DELETED）。
- chunkOwners=实库 `document_chunk_index` IN 查询，非 mock。✓

### 3.9 KnowledgeDocumentPO / DocumentLifecycleStatus
- PO 列含 version、lifecycleStatus、tenantId/deptId/deviceId（D39 三列）——**P2 版本指针更新与 lifecycle 标记复用既有列，零 DDL**（任务书"既有列，无 DDL"确认）。
- KnowledgeDocumentMapper=纯 BaseMapper（P2 追加 update 方法或 UpdateWrapper 皆可，走追加式）。
- LifecycleStatus：DEPRECATED.isEffective()=false（fail-closed 全集除 ACTIVE）。

### 3.10 配置与 DDL 形态
- application.yml：`app.pgvector.table=${PGVECTOR_TABLE:d40_vector_store}`、initialize-schema=${PGVECTOR_INIT_SCHEMA:true}（新表预计 0 行，KP2 先例处置=seeding）；hybrid expected-fingerprint 默认=10fc69d5…；access.enabled=${ACCESS_ENABLED:false}；MySQL 主源 ai_db。
- 既有 app.vector-index 段实测值：default-strategy/batch-size/max-attempts/stuck-minutes/retry-backoff-ms/split-version/reconcile-*（VectorIndexProperties 默认与任务书 §1.1 逐项一致：100/5/30/500ms/split-v1/false/`0 0 2 * * *`）。**无 fault-injection/sync-retry/audit 子段**——P1/P3 仅追加。
- MySQL DDL 先例：resources/db/schema-document-vector-index.sql（CREATE TABLE IF NOT EXISTS、uk_doc_version、uk_chunk_id 等）——P1 sync/audit DDL 按同体裁追加文件。
- 冻结语料：`D40_DUDACR\src\main\resources\knowledge\equipment-fault-corpus.json`（corpus-location 配置行 111；3 文档 8 片段，D37 口径）。
- seeding 脚本参照：docs\temp_docs\D39\d39_index-corpus.ps1（先 DELETE 本 3 文档再 STRUCTURE version=1 重建，稳定 UUID v5 确定性）。

## 4. 与任务书 §1.1 的差异（如实记录）
- 唯一差异：§1.1 "get/pages/chunks 有 checkReadable"——实测 **get 无 checkReadable**（仅 pages/pages/{n}/chunks/chunks/comparison 有）。该差异属 D39 已交付行为，非本轮改动对象；KP1 inventory 按实测分级留痕，不据此扩大白名单。
- 注释漂移确认（H1 不改）：yml 注释引用 docs/evidence/D40/hybrid/probe/hybrid-params.json 等路径尚不存在。

## 5. 结论
基线与任务书编制口径一致，P0 可进。风险预警：d40_vector_store initialize=true 新表 0 行 → 熔断#2 预案=先 seeding（D39 KP2 先例）；MySQL 共享表遗留行须 KP2 清单冻结。
