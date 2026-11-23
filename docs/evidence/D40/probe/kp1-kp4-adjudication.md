# D40-R01 P0 探测门禁裁决记录（KP1–KP4，2026-11-23）

依据任务书 §1.3：全部结论出自实扫/实测/实读，参数冻结于 `sync-params.json`。

## KP1 写面暴露分级 + 审计设计

- 端点实扫：`Get-ChildItem *Controller.java` + 逐方法正则提取 mapping 注解，**53 行台账**（50 既有方法 + 3 本轮新建 sync 端点）= `endpoint-inventory.csv`。
- 分级裁决（D6 收口范围）：
  - **ADMIN_WRITE（本轮追加 requireAdmin+审计，8 既有）**：vector-index index/indexFromPages/delete/reconcile/recover + documents register/import；
  - **ADMIN_WRITE（本轮新建，3）**：GET /api/v1/sync/status、POST /api/v1/sync/retry、POST /api/v1/sync/consistency；
  - **ADMIN_EXISTING（已收口，核验不动）**：vector-store raw 3 端点、hybrid keyword-index/rebuild；
  - **DOC_SCOPE_PREVIEW（D39 已收口不动）**：vector-index status/chunks、documents pages/page/chunks/comparison、documents/{id}/embeddings（实测 @PostMapping）；
  - **SEARCH_PREDICATE（谓词内嵌不动）**：retrieval/search、rag/ask、hybrid search/ask；
  - **OUT_OF_SCOPE（列 D41，防范围蔓延）**：Embedding model/compatibility、History/sessions、PromptTemplate 7 端点、one_ 轨 9 端点、device/chat、chat/stream、documents get/list（读面）。
- 实测差异：`GET /documents/{id}` **无** checkReadable（任务书 §1.1 称有）——按实测分级为 DOC_SCOPE_READ 待 D41，本轮不扩面（H1 白名单），差异已留痕 baseline-review §4。
- 审计 schema 冻结：`d40_admin_audit`（actor_user_id/tenant_id/role/action/target_document_id/version/result/reason_code/request_id/created_at）；写入时机=门禁校验**之后**（DENIED 也落审计，ALLOWED 同步落），disabled 时不写（零差异）。requestId 复用既有 requestId 链路（logging 包 MDC/requestId 先例）。

## KP2 存储现状 + seeding + 指纹

- PG `d40_vector_store`：表存在（initialize-schema=true 已自动建表）、**0 行**、metadata 列类型 **json**（非 jsonb，D39 口径沿用）、one_d40_vector_store NOT_EXIST。证据 `kp2-inventory.json`。
- MySQL 共享表盘点：knowledge_document=3 行（DOC-* v1 COMPLETED ACTIVE，权限三列全 null）；document_vector_index=7（DOC-*3+d32eval-*4）；document_chunk_index=32（DOC-*8+d32eval-*24）；d39_auth_principal 存在（5 principal，D39 资产只读）；**d40_auth_principal/d40_document_sync/d40_admin_audit 均不存在**（P1 新建）。
- 遗留行清单冻结（清理白名单唯一依据，H6）：DOC-* 3 文档 + d32eval-* 4 文档共 7 任务行/32 关联行/8+24 向量引用域 → **全部零触碰**；本轮可清理域仅 `d40-` 前缀。
- **seeding（熔断#2 预案执行）**：`d40_index-corpus.ps1`（D39 先例改编）经 /vector-index 管线 先 DELETE（removed=3/3/2）后 INDEX（全 200/COMPLETED/CLEANUP）。
- 指纹复算（harness 同 InvertedIndex 算法）：**8 行、`10fc69d5c9c3fd088b98578d74278135b1c04b7cd8b29b361b4c983926567af9` 逐字节一致 = 冻结基线值**；QA-001 锚点 chunkId `3b1501c0-6a99-535a-a787-991f877024c8` 在位。C04 PASS。

## KP3 故障注入 + 引用可观测性

- **主手段冻结**：`FaultInjectingVectorStore` 装饰器 implements VectorStore，包装 `pgVectorStore` Bean（@Primary 消歧，D38 教训）；装配门禁 `app.vector-index.fault-injection.enabled` 默认 false、**无 matchIfMissing → 主 profile 永不装配**；触发语义=fail-add-attempt/fail-delete-attempt 各为调用序号（0 禁用，命中仅抛一次），受控异常 `SimulatedConnectionLostException("SIMULATED_PG_CONNECTION_LOST")`；PG 本体不动（H5 合规：失败点在客户端边界≡连接丢失）。
- **辅手段**：坏端口启动 fail-fast（PGVECTOR_PORT=59999，继承 D39 C16 纯 env 双演练先例）。
- **回归隔离**：注入态与 P6 回归态实例分离，残留装配→熔断#6。
- 引用交叉验证路径实证：`CitationVerificationService.classify` 实读——tombstone 场景（doc 行保留 DEPRECATED + chunk 行清除）命中 **chunkId∉chunkOwners 分支** → CITATION_TARGET_DELETED（非 doc-null 分支）；chunkOwners 来自实库 document_chunk_index IN 查询（非 mock）。生成中删除竞态 mock 不可观测 → N/A（OneRagOrchestrator 既有注释口径）。C17 可断言。

## KP4 状态机 + 原因码 + 漂移分类 + 幂等指纹（四冻结见 sync-params.json）

关键裁决（含对任务书的两处口径细化，均有依据）：

1. **d40_document_sync.version 由 NULL 改 NOT NULL DEFAULT 0**：MySQL 唯一键对 NULL 不判重，`uk_doc_op_version` 防重复事件目标会失效（E4 直接依据）；0=全版本（DELETE 语义）、>0=目标旧版本（UPDATE_CLEANUP）。偏差留痕，语义与任务书 D1 表设计一致。
2. **RETRY_EXHAUSTED 表示法**：任务书枚举 sync_status 仅三态，故耗尽态=`sync_status=FAILED ∧ reason_code=RETRY_EXHAUSTED ∧ attempt≥max`（非第四枚举值）；ADMIN 手动 retry 对耗尽行重置 attempt=0 再执行（审计 ALLOWED+retryOnExhausted 留痕）。
3. 原因码 10 项分组冻结：sync 侧 6（PG_UNREACHABLE/PG_DELETE_FAILED/CONFIRM_MISMATCH/MARK_FAILED/SNAPSHOT_MISSING/RETRY_EXHAUSTED）+ 既有 4 语义保留原位（索引任务 error_code 列、导入 DocumentStatus FAILED），不新建存储位。
4. 异常→原因码分类规则冻结（含 SimulatedConnectionLost→PG_UNREACHABLE 的注入映射）。
5. 双端确认判据冻结：DELETE=全版本 PG count=0 ∧ 关联行=0 ∧ 任务行=0；UPDATE_CLEANUP=目标版本双 count=0。
6. 幂等四元组冻结：payloadHash + sorted chunkIds SHA-256 + PG count + 关联 count，前后逐字节一致才算幂等（C07/C10/E2 判定器）。
7. 漂移五类判定冻结（含 LEGACY_OUT_OF_SCOPE 口径：d32eval-* 24 missing 属 D32 历史资产，如实列出、不修复、不入归零分母——避免误删他人数据，H6 与 C13 的边界裁决）。
8. sync 卡滞判定含 PENDING 超 stuckMinutes（崩溃滞留场景）。

## P0 结论

KP1=PASS、KP2=PASS（seeding+指纹一致）、KP3=PASS（手段可控可复现）、KP4=PASS（四冻结生成）。可进 P1。

## KP5 追加裁决（P5-C12b，2026-11-23 18:1x，实例 B 执行时冻结）
- C12 要求五类漂移"全命中"。META_ONLY/STALE_VERSION/SYNC_STUCK 已有管线自然命中（E2/E5 注入+既有域外数据）；
  **VECTOR_ONLY 与 VERSION_MISMATCH 无任何注入点/管线入口可自然构造**（装饰器仅 add/delete 抛；无删关联行或改指针的 API）。
- 裁决（与既有"up 指针 DB 级单步构造+留痕"先例同型）：允许**单步、最小、留痕**的 DB 级构造——
  (1) DELETE d40_vector_store 中 d40-doc-j 1 行（PG 元数据完整可回放）→ META_ONLY(d40 域自然形态，同时验证重建修复)；
  (2) DELETE document_chunk_index 中 d40-doc-k 全部关联行（chunkId+content 已在构造前快照留痕）→ VECTOR_ONLY；
  (3) UPDATE knowledge_document.version d40-doc-k 2→3 → VERSION_MISMATCH(a)；UPDATE d40-doc-j version 3→9 → 无 COMPLETED v9=漂移(b)。
- 边界：修复动作 100% 走管线（deleteVectorsByIds/indexChunks/repointVersionPointer）；构造行限 d40-doc-j/k 一次性夹具；禁 DROP/TRUNCATE/跨前缀。熔断#4 不命中（非推进所迫，属测试场景构造且先留痕后修复）。
