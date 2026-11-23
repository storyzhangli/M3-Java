# D40 项目迭代任务书 Prompt（R01）：文档更新删除与一致性修复（one 仓）

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 结构化记录生文档。沿用 D34–D39 体裁。
> 核心原则：**跨系统一致性靠同库状态机 + 幂等管线 + 对账补偿，不靠消息中间件，不靠分布式事务，不靠破坏性直改。更新先写新再切指针再清旧；删除先标记再清向量再双端确认；失败必落库、可查询、可重试、可收敛。一致性修复是特权操作，写面必须 ADMIN-only + 审计留痕。**
> 编制日期：2026-11-23。编制依据：对 `E:\AI\project\AI-Learning-one\M3-Java\D40` 骨架（HEAD `f3cf475`，已提交、工作区干净）与 D37/D39 冻结资产的真实扫描（§1），非记忆假设。
> 硬约束：以本地实际代码为唯一依据；白名单内最小必要修改；不伪造指标与测试结果；空检索≠检索失败；核心契约禁用无约束 Map；保留熔断与 Token 阈值模板；**禁止 Kafka/RocketMQ 等中间件**。

---

## §0 迭代总览与硬契约

### 0.1 目标

在 D40 模块（`D40\D40_DUDACR`，包 `com.zl.d40`，端口 **8080**）实现文档版本更新（v1→v2）与删除的**同步闭环 + 失败状态管理 + 一致性核查面**：新增 `sync_status`（PENDING/SYNCED/FAILED）操作级状态机与细分原因码；删除/旧版本清理失败落库、可手动/自动重试收敛；一致性核查输出四类漂移 + 同步卡滞，修复走既有入库/删除管线；写面端点 ADMIN-only + 审计（衔接 D39 遗留）；五类异常矩阵 + 引用删除交叉验证全部真实执行留证。

### 0.2 硬契约（违反任意一条即任务失败）

| 编号 | 契约 | 说明 |
|---|---|---|
| H1 | 白名单最小修改 + 门禁零差异 | 既有文件改动仅限 §2 P2/P3/P4 白名单，全部追加式。**access 门禁面**（ADMIN 校验/审计）在 `app.access.enabled=false`（默认）时零装配零行为差异（C05 用 D37 冻结基线 60 行 + 匿名探针证明）。**核心同步闭环**（sync 状态机/版本指针/清理钩子）是本轮主链路功能，允许改变写路径内部行为，但必须保持既有端点响应契约不变且通过 D37 回归（读路径质量字段逐字节一致）。白名单外既有文件零改动。 |
| H2 | 新开关默认关闭 | `app.vector-index.fault-injection.enabled` 默认 false（**主 profile 永不装配**，仅测试显式开启）；`app.vector-index.sync-retry.enabled` 默认 false（自动重试调度关闭，手动重试端点恒可用）；审计随 access.enabled 门禁。sync 状态机本身随删除/清理主链路恒生效（功能本体，非可选开关）。 |
| H3 | 禁消息中间件 | 一致性机制=同库状态机 + 幂等管线 + 对账补偿；**禁止引入 Kafka/RocketMQ/RabbitMQ 等任何 MQ**，禁止分布式事务框架。既有 Outbox 快照（document_chunk_index 含 content）+ 稳定 UUID v5 + DB 级 Upsert 是唯一恢复载荷来源。 |
| H4 | 修复走管线禁直改 | 一致性修复动作只经既有管线（indexChunks 幂等重建 / deleteVectorsByIds 补偿删除 / recoverStuckTasks / sync 重试），**禁止对业务表做破坏性裸 SQL UPDATE/DELETE 直改**（夹具清理除外，且须前缀限定 + 留痕）。对账 dryRun 只出报告。 |
| H5 | 受控故障注入 | 故障注入仅用 §3.5 冻结的受控手段（测试门禁装饰器 + 坏端口启动 fail-fast 演练）；**禁止**防火墙改规则、停 PG 服务、pg_terminate_backend、DROP 表、Docker prune/删卷/重建容器等破坏性操作。 |
| H6 | 冻结资产只读 + 夹具可清理 | D37 数据集（SHA `75cc8d83cfb2…`）/基线（baseline.csv 60 行）/评测器、D38/D39 全部证据只读；夹具一律 `d40-` 前缀；**MySQL 业务表跨 Dxx 共享**（knowledge_document/document_vector_index/document_chunk_index 与 D39 同名同库），清理必须前缀限定，遗留语料 3 文档 8 片段与任何 D39 行零触碰；测试后 PG/MySQL/sync/audit 多处核验 FIXTURE_REMAINING=0；hybrid 指纹恢复 `10fc69d5…`（D5  seeding 后须一致）。 |
| H7 | 特权写面 ADMIN-only + 审计 | access.enabled 时，vector-index 写面（index/indexFromPages/delete/reconcile/recover）+ documents register/import + 新增 sync/consistency 端点必须 `requireAdmin`（服务端 role 判定，非请求声明）+ 审计落库；**不得开出不鉴权的修复面**。disabled 时维持开发态零差异（D39 先例）。 |
| H8 | 诚实记录 | 编译/测试/异常矩阵真实执行；四态 PASS/FAIL/BLOCKED/NOT RUN 逐用例落表；幂等以**前后指纹对比**证明而非口头断言；mock 下不可观测的删除竞态如实标 N/A（OneRagOrchestrator 既有注释先例），不伪造。 |
| H9 | 凭据与预算卫生 | 密钥仅环境变量（PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY）；夹具 token 明文仅 temp_docs（不提交），DB 只存 SHA-256；日志/证据/Git 对 token 明文与 sk-/Bearer 实值扫描零命中；surefire-reports/target 禁提交；temp_docs 不自动删除；不 push。 |

### 0.3 范围边界（本轮 R01 明确不做）

- 消息中间件/分布式事务/事件总线：不做（H3 红线）。
- Spring Security 全栈/JWT/OAuth2 升级：不做（沿用 D39 最小 Bearer 凭据机制）。
- D39 其余遗留写面（Embedding model/compatibility、HistoryController 明细、PromptTemplate 管理面）：本轮不收口，列 D41 候选（D6 裁决）。
- one_ 轨真实端到端、SSE 流式全路径权限：NOT RUN（零远程预算，沿用 D39 边界）。
- 文档硬删除（knowledge_document 行物理删除）：不做默认行为（D2 tombstone 裁决）；生命周期标签迁移方案留后续。
- 跨机 L5：NOT RUN（单机）。

### 0.4 与用户原始需求的九处已裁决偏差/细化（执行前必读，均有实扫依据）

| # | 用户原文 | 实测事实 | 裁决 |
|---|---|---|---|
| D1 | 「sync_status（PENDING/SYNCED/FAILED）+ 细分原因码」 | 全仓 grep **无 sync_status/syncStatus**；既有 document_vector_index 有 status/stage/errorCode/attemptCount/nextRetryAt（索引任务级 6 态），但**删除路径 deleteByDocument 是同步 fire-and-forget**：PG 失败异常直接上抛，无 FAILED 落库、无重试队列、无操作级同步状态 | 新建**操作级**同步表 `d40_document_sync`（document_id/operation[DELETE|UPDATE_CLEANUP]/version/sync_status/reason_code/attempt_count/next_retry_at/payload_hash/created_at/updated_at），与既有索引任务状态机**正交共存**（索引态管入库，同步态管删除/清理跨系统收敛）；不改 document_vector_index 既有语义 |
| D2 | 「元数据标记删除 → 向量清除 → 双端确认」 | deleteByDocument 只清 PG 向量 + document_chunk_index/document_vector_index 行，**不触碰 knowledge_document 行**（D39 C19 实测确认，夹具清理曾需 DB 级 DELETE）；KnowledgeDocument 有 lifecycleStatus（DRAFT/ACTIVE/DEPRECATED） | 删除闭环=**tombstone 标记**（lifecycleStatus→DEPRECATED，与 sync PENDING 同 MySQL 事务）→ PG 分批删除 → stateTx.deleteDocumentRows 清关联/任务行 → 双端确认（PG count=0 ∧ 关联行=0）→ SYNCED。**knowledge_document 行保留为 tombstone**（审计 + 引用核验 doc 存在语义不变 + 避免硬删改变 CITATION 分类路径）；硬删非默认，留后续裁决 |
| D3 | 「v1→v2 原子切元数据版本」 | indexChunks 已实现「新块先写 → SWITCH（stateTx.switchVersion MySQL 本地事务：旧 COMPLETED→SUPERSEDED，新→COMPLETED）→ cleanupSuperseded」；但 **knowledge_document.version 不随 SWITCH 更新**（register 恒 setVersion(1)，无 version bump 端点）→ 文档元数据版本与活动索引版本天然漂移 | switchVersion **追加式**在同一 MySQL 事务内更新 knowledge_document.version=targetVersion（版本指针权威化）；此为核心写路径改动（非 access 门禁），须过 D37 回归证明读路径质量字段不变；「版本不一致」漂移由此可检测可修复 |
| D4 | 「旧版本向量清除；中途失败可重入」 | cleanupSuperseded 失败**仅 log.warn**（"非致命…留给核对补偿"），无状态落库、无重试任务；对账 orphan 检测可兜底但无操作级收敛追踪 | cleanupSuperseded 失败 → 建 UPDATE_CLEANUP sync 任务 FAILED + 原因码（钩子追加式）；重试经 sync 管线收敛；成功 → SYNCED。稳定 UUID v5（含 version/strategy/chunkContentHash/splitVersion）+ DB Upsert 保证重入幂等 |
| D5 | 「一致性核查输出四类漂移」 | VectorReconciliationService 已覆盖 orphanVectors（向量在/元数据缺）、missingVectors（元数据在/向量缺）、countMismatches、stuckTasks；**未覆盖 knowledge_document.version vs 活动索引版本不一致**，无 sync 卡滞检查；hybrid 指纹门禁 CORPUS_DRIFT fail-fast 仍在（expected=10fc69d5…，D39 语料值）；d40_vector_store initialize=true 新表**预计 0 行**（D39 KP2 曾遇 d39 表 0 行先例） | 四类漂移映射：DRIFT_META_ONLY=missingVectors、DRIFT_VECTOR_ONLY=orphanVectors、DRIFT_VERSION_MISMATCH=**新增**（文档版本≠活动 COMPLETED 版本 / PG metadata.version≠关联行 version）、DRIFT_STALE_VERSION=SUPERSEDED 残留（orphan 子集显式标类）；**另加 DRIFT_SYNC_STUCK**（FAILED 超期/重试耗尽）。对账追加式扩展 + 修复走管线（H4）。KP2 先 seeding（d39_index-corpus.ps1 模式）使 d40 表=8 片段、指纹=10fc69d5… 方可启 hybrid |
| D6 | 「写面端点必须 ADMIN-only + 审计留痕」 | D39 已交付 access 包（requireAdmin/requireDocument/andWithAccess，deny-code 4030，d40_auth_principal）；PgVectorController raw 端点已 requireAdmin ✓；**VectorIndexController 写面（index/indexFromPages/delete/reconcile/recover）与 DocumentController register/import/list 未门禁**（D39 状态文件「遗留风险」显式列 D40 候选）；无审计表 | 本轮收口=vector-index 写面 + documents register/import + 新增 sync/consistency 端点，ObjectProvider 门禁（disabled 零差异）+ 新表 `d40_admin_audit`（actor/tenant/role/action/target/version/result/reason_code/request_id/created_at）；Embedding/History/PromptTemplate 其余遗留**不在本轮范围**（避免范围蔓延，列 D41） |
| D7 | 「故障注入用受控手段，禁破坏性操作」 | D39 T8 仅做**启动期**坏端口 fail-fast 演练；状态文件明示「运行中掉线瞬间行为」未验证（以启动 fail-fast + 静态核验替代）——本轮删除重试恰需**运行期** PG 失败 | 主手段=**测试门禁装饰器** FaultInjectingVectorStore（app.vector-index.fault-injection.enabled 默认 false，仅测试显式开启时包装真实 VectorStore Bean，按调用计数在第 N 次 add/delete 抛受控异常；PG 本体真实不动，失败点在客户端边界≡连接丢失）；辅手段=坏端口启动 fail-fast 演练（继承 D39 T8）。禁防火墙/停服务/DROP/prune（H5） |
| D8 | 「删除后引用核验命中 CITATION_TARGET_DELETED（D35 链路不回退）」 | CitationVerificationService.classify：doc==null 或 chunkId 不在 chunkOwners（document_chunk_index 实查）→ CITATION_TARGET_DELETED；ownership 不一致→CITATION_OWNERSHIP_MISMATCH；lifecycle 非 effective→EVIDENCE_NOT_EFFECTIVE。OneRagOrchestrator 注释明示「删除竞态在 mock 下不可观测，如实 N/A」 | 交叉验证走**确定性删除后核验**：夹具入库→构造 EvidenceItem→真实删除（chunk 行清除）→CitationVerificationService 实库核验→断言 removed 原因=CITATION_TARGET_DELETED（真实核验链，非 mock 绕过）；**生成中删除竞态** mock 下不可观测→如实 N/A（沿用既有注释口径），不伪造端到端 |
| D9 | 「D40 基础骨架仓库」 | HEAD f3cf475 骨架**已提交**、工作区干净（区别于 two-lp D39 未提交先例）；D40 src/test **0 个 Java 测试**（D39 的 29 个单测在 D39 模块未复制）；前端 front-end-safety.test.js 在 | 无需先提交骨架；本轮**新建 D40 单测**（sync 状态机/原因码/幂等指纹/门禁/审计），前端测试零回归；MySQL 共享表夹具隔离按 H6 前缀限定 |

---

## §1 基线核实结果（编制时真实扫描，执行时须复核）

### 1.1 已核实事实（证据均为实读/实测）

| 项 | 实测值 |
|---|---|
| Git 基线 | HEAD `f3cf475`（D40 项目基础骨架，已提交，工作区干净）；前序 `1ca6634`（D39 R01 知识权限，108 files +4697/-29）、`da180ef`（D39 骨架）、`ccd7f9f`（D38 R02） |
| 模块 | `D40\D40_DUDACR`，包 `com.zl.d40`，端口 **8080**；main Java **308 个**；顶层包 access/adapter/common/config/controller/conversation/device/document/dto/history/hybrid/knowledge/logging/observability/one/prompt/rag/resilience/service |
| 技术基线 | Spring Boot 3.5.16 / JDK21 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17（pom 与 D39 同基线） |
| 索引状态机 | VectorIndexStatus 6 态：PENDING→INDEXING→COMPLETED；异常→PARTIAL（有部分写入）/FAILED（无有效写入）；版本切换旧→SUPERSEDED。IndexStage 4 阶段：SPLIT→EMBED_WRITE→SWITCH→CLEANUP（记录最后完成阶段，恢复从其后幂等续跑）。ChunkRowStatus：INDEXED/FAILED |
| 稳定 ID | StableChunkIdGenerator：RFC 4122 UUID v5（SHA-1），name=`documentId\|version\|strategy\|chunkIndex\|chunkContentHash\|splitVersion`，固定命名空间 `0f9f7d52-6b3e-4a1c-9f2d-7c8e5b3a6d41`；同输入恒同 ID，splitVersion 变更旧 ID 不复用 |
| 入库管线 | DocumentVectorIndexService.indexChunks：幂等短路（COMPLETED ∧ payloadHash 一致 ∧ 关联数一致→idempotent=true 零写入）→ INDEXING/SPLIT → lookupDocumentScope（D39 权限范围服务端权威列）→ 稳定 ID + 内容指纹 → 分批 vectorStore.add（**框架原生 DB 级 Upsert**）+ chunkMapper.upsertOnNaturalKey → 写后核对（PG 数=关联数=Chunk 数，否则 PARTIAL/COUNT_MISMATCH）→ stateTx.switchVersion → cleanupSuperseded（**失败仅 warn**）→ COMPLETED/CLEANUP。并发首建 DuplicateKeyException 读胜出行续跑（uk_doc_version） |
| 删除现状 | deleteByDocument：分批 vectorStore.delete（PG 对不存在 id 不报错，重试安全）→ stateTx.deleteDocumentRows（清 chunk+index 行，**PG 成功后才清 MySQL**）；**无 sync_status、无 FAILED 落库、无重试、不触碰 knowledge_document 行**（D39 C19 实测） |
| 事务边界 | VectorIndexStateTx：switchVersion/deleteDocumentRows/deleteOldVersionRows 均 @Transactional MySQL 单库本地事务，**不含 PG/Embedding 调用**；跨系统一致性由状态机 + 核对补偿保证（无分布式事务） |
| 恢复 | recoverStuckTasks：PENDING/INDEXING 超 stuckMinutes（30）→ Outbox 快照（document_chunk_index.content）幂等重跑；attemptCount≥maxAttempts（5）跳过待人工；无快照→FAILED/NO_SNAPSHOT |
| 对账 | VectorReconciliationService.reconcile(dryRun)：orphanVectors（PG 有但无有效关联/任务非活动）、missingVectors（关联有 PG 缺）、countMismatches（版本级关联数 vs PG 数）、stuckTasks；非 dryRun 补偿=删孤儿 + 快照幂等重建 + recoverStuck；pageMetadataCoverage 只读覆盖度。isActive=COMPLETED/INDEXING/PARTIAL/PENDING（SUPERSEDED/FAILED 非活动→其向量判孤儿） |
| 调度 | VectorIndexSchedulingConfiguration：@ConditionalOnProperty reconcile-enabled=true（默认 false），@Scheduled cron `0 0 2 * * *` 执行 reconcile(false) |
| 索引配置 | app.vector-index：defaultStrategy=STRUCTURE、batchSize=100、maxAttempts=5、stuckMinutes=30、retryBackoffMs=500、splitVersion=split-v1、reconcileEnabled=false、reconcileCron=0 0 2 * * * |
| 既有原因码 | EMBED_WRITE_FAILED、COUNT_MISMATCH、NO_SNAPSHOT（索引侧）；DocumentStatus FAILED（导入侧）；**无删除/清理同步原因码** |
| 文档域 | DocumentController /api/v1/documents：register/import/get/list/pages/page/chunks/comparison；**无文档级 DELETE、无 lifecycle 更新端点**；register 恒 version=1；get/pages/page/chunks 有 checkReadable（D39），register/import/list 未门禁。KnowledgeDocument：knowledge_document 表，version/lifecycleStatus（DRAFT/ACTIVE/DEPRECATED，isEffective）/tenantId/deptId/deviceId（D39 三键） |
| 引用核验 | CitationVerificationService.classify：doc==null→CITATION_TARGET_DELETED；chunkId 空/不在 chunkOwners（document_chunk_index 实查）→CITATION_TARGET_DELETED；归属不一致→CITATION_OWNERSHIP_MISMATCH；lifecycle 非 effective→EVIDENCE_NOT_EFFECTIVE。RagOrchestrator/HybridRagOrchestrator/OneRagOrchestrator 均映射 CITATION_TARGET_DELETED；OneRagOrchestrator 注释：mock 下删除竞态不可观测如实 N/A |
| 权限（D39 已交付） | access 包 14 文件；AccessPolicyService：requireCurrent/currentScope/vectorPredicate/andWithAccess/canReadDocument/requireDocument/**requireAdmin**（ADMIN_REQUIRED）；enabled 默认 false、sentinel `*`、deny-code 4030、凭据表 **d40_auth_principal**（SHA-256）；PgVectorController raw /documents /search /stats 已 requireAdmin；**VectorIndexController 写面未门禁**（注释"无鉴权"） |
| 检索 | SimilaritySearchService：业务过滤 documentId/title/version/strategy + policy.andWithAccess（D39 注入）；hybrid 默认关，**CORPUS_DRIFT fail-fast 门禁仍在**（InvertedIndex 构建期复算指纹≠expected 即抛），expected=`10fc69d5c9c3…`（D39 语料值） |
| 存储 | PG 192.168.200.153:5432/spring_ai：**d40_vector_store**（local 384，app.pgvector 前缀，initialize-schema=**true**，预计 0 行待 seeding）+ one_d40_vector_store（one_ 1024，默认关 dims=0）；PG_VECTOR_PASSWORD fail-fast。**MySQL ai_db 业务表跨 Dxx 共享**：knowledge_document/document_vector_index/document_chunk_index（D39 PO 同名实测）+ d40_auth_principal；MYSQL_PASSWORD 本机默认 123456 |
| Embedding/切分 | local-hashing-embedding/local-v1/384/COSINE，max-input 8192；chunk 500/100（UTF-16，min 50/max 1000）；ai.mode=mock 默认；resilience 包（maxAttempts=2，retry-on-read-timeout=false） |
| 冻结资产 | D37 数据集 20 问（`75cc8d83cfb2…`）+ 基线 `docs\evidence\D37\baseline\v1.0\PGVECTOR\baseline.csv`（60 行）+ 评测器（temp_docs\D37\evaluator + evidence\D37\evaluator）；D39 回归口径 QUALITY_FIELDS_IDENTICAL 60/60 + QA-001 top1 delta=0 + 匿名探针零差异；D39 seeding 脚本 `temp_docs\D39\d39_index-corpus.ps1`、凭据种子 `D39AuthSeed.java`、夹具 token `temp_docs\D39\auth\fixture-tokens.json`（只读参照）；D39 冻结指纹 baseline=10fc69d5…/fixture=388a593c… |
| 测试现状 | D40 src/test **0 个 Java 测试**（D39 的 29 个在 D39 模块未复制）；frontend\front-end-safety.test.js（node --test 6/6 先例）+ application-test.yml |
| 目录 | docs\evidence\D40 与 docs\temp_docs\D40 编制时**不存在**（本轮新建）；logs\D40 已存在；proj_docs 已有 D40-知识笔记-DB-…（**D40-01/02 编号可用**） |
| 已知注释漂移 | application.yml hybrid/access 段注释引用 `docs/evidence/D40/hybrid/probe/hybrid-params.json`、`docs/temp_docs/D40/auth/fixture-tokens.json`（骨架自 D39 复制改注释，路径尚不存在）；不改既有注释行（H1），KP2 如实记录 |
| 工具链 | Maven `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`（JDK21，本地仓库 D:\SoftwareInstall\Apache\repository，勿传 -Dmaven.repo.local）；PowerShell 7；`[Console]::OutputEncoding=UTF8`；无 mysql CLI（jshell/JDBC 工具类先例 D39AuthSeed.java） |

### 1.2 执行时复核命令（PowerShell 7，逐条留痕）

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-one\M3-Java"
git -C $r rev-parse HEAD; git -C $r status --porcelain   # 须 f3cf475 且干净
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "$r\D40\pom.xml" -pl D40_DUDACR -q compile
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
(Get-FileHash "$r\docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl" -Algorithm SHA256).Hash  # 须 75cc8d83cfb2 开头
# 应用起来后基线端点（端口 8080；Result.code 判成败，HTTP 恒 200）
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/vector-index/documents/DOC-CNC-COOLING-001/status" -TimeoutSec 5 | ConvertTo-Json -Depth 4
```

### 1.3 探测门禁（P0 裁决，证据落 `docs\evidence\D40\probe\`）

| 门禁 | 待核实项 | 方法 | 通过判据 | 失败处置 |
|---|---|---|---|---|
| KP1 | 写面暴露分级 + 审计设计 | 遍历全部 @*Mapping，逐端点分级：ADMIN 写面/DOC_SCOPE 预览/PUBLIC/范围外，输出 endpoint-inventory.csv；确认 D39 遗留收口范围（D6）；冻结 d40_admin_audit 列与写入时机 | 每个写面端点有门禁裁决；审计 schema 冻结；范围外端点列 D41 不遗漏 | 发现未分级特权面 → 补裁决后才能进 P3 |
| KP2 | 存储现状 + seeding + 指纹 | JDBC 只读盘点 d40_vector_store 行数（预计 0）、MySQL 共享表按 documentId 前缀分布（遗留 DOC-* / D39 残留 / 无 d40-）、d40_auth_principal 列与行数；**seeding**：仿 d39_index-corpus.ps1 经 /vector-index/index 入 classpath 语料 3 文档 8 片段 → 复算 hybrid 指纹须=`10fc69d5…`；记录 knowledge_document.version 现状 | d40 表=8 片段、指纹逐字节一致；MySQL 遗留行清单冻结（清理白名单依据）；凭据表可种子 | 0 行→先 seeding（D39 KP2 先例）；指纹不等→CORPUS_DRIFT 熔断查语料/Embedding/splitVersion，禁带漂移推进 |
| KP3 | 故障注入 + 引用可观测性 | 冻结 FaultInjectingVectorStore 装配门禁（仅测试 property 显式 true）、触发语义（第 N 次 add/delete 抛受控异常）、与真实 Bean 的包装关系；冻结 CITATION_TARGET_DELETED 确定性核验路径（真实 CitationVerificationService + 实库 chunk 行删除），确认 mock 竞态 N/A 口径 | 注入手段受控可复现、主 profile 永不装配；引用交叉验证路径真实可断言 | 注入手段不可控→换受控方案，禁破坏性操作（H5）；引用路径不可观测→如实 N/A 不伪造 |
| KP4 | 状态机 + 原因码 + 漂移分类 + 幂等指纹 | 冻结 sync 状态机（PENDING/SYNCED/FAILED + 重试迁移）、原因码表（§3.2）、四类漂移 + SYNC_STUCK 判定 SQL/口径、幂等指纹方法（payloadHash + 排序 chunkId 集 + PG 数 + 关联数） | 状态机/原因码/漂移/指纹四冻结文件生成，后续实现测试一律引用 | 口径冲突→先裁决冻结，禁实现期私自改口径 |

---

## §2 分阶段实施要求

### P0 探测与参数冻结

KP1–KP4 裁决留痕；seeding 使 d40_vector_store=8 片段、指纹=10fc69d5…；冻结 `probe\sync-params.json`：`{syncTable, auditTable, reasonCodes, driftTaxonomy, fingerprintMethod, faultInjectionGate, baselineFingerprint, datasetHash, legacyDocIds, skeletonHead, probedAt}`。

### P1 同步状态机（新包 `com.zl.d40.document.sync`，全部新文件）

1. DDL（幂等 ×2 零报错）：`d40_document_sync`（id、document_id、operation ENUM('DELETE','UPDATE_CLEANUP')、version NULL、sync_status ENUM('PENDING','SYNCED','FAILED')、reason_code、attempt_count、next_retry_at、payload_hash、created_at、updated_at；唯一键防重复事件 uk_doc_op_version）+ `d40_admin_audit`（id、actor_user_id、tenant_id、role、action、target_document_id、version、result、reason_code、request_id、created_at）。
2. `SyncStatus`/`SyncOperation`/`SyncReasonCode` 枚举；`DocumentSyncPO`/`Mapper`（upsertOnNaturalKey）；`DocumentSyncService`：markPending（与元数据标记同 MySQL 事务）→ markSynced（双端确认后）→ markFailed（原因码 + attempt + nextRetryAt 退避）→ retry（手动恒可用 / 自动调度门禁）→ 重试耗尽 RETRY_EXHAUSTED 待人工（ADMIN 可重置）。
3. `SyncRetryScheduler`：@ConditionalOnProperty sync-retry.enabled=true（默认 false），扫描 FAILED ∧ next_retry_at 到期 ∧ attempt<max → 经管线重试；与既有 reconcile 调度正交。
4. `D40SyncController`（新端点，ADMIN 门禁 + 审计）：GET /api/v1/sync/status（失败可查询）、POST /api/v1/sync/retry（手动重试）、POST /api/v1/sync/consistency（一致性核查，dryRun 默认 true）。

### P2 删除闭环 + 版本指针 + 清理钩子（白名单追加式）

| 文件 | 改动 |
|---|---|
| DocumentVectorIndexService | deleteByDocument 重构为同步闭环：markPending（lifecycle→DEPRECATED + sync PENDING 同事务）→ PG 分批删除 → deleteDocumentRows → 双端确认（countPgForVersion 全版本=0 ∧ listChunks=0）→ markSynced；失败 markFailed + 原因码（异常仍上抛但状态已落库）。cleanupSuperseded 失败钩子→建 UPDATE_CLEANUP FAILED 任务（替代仅 warn）。**追加式，既有 indexChunks 主流程不动** |
| VectorIndexStateTx | switchVersion 追加 knowledge_document.version=targetVersion（同事务）；新增 markDeletedTx（lifecycle + sync PENDING 原子）；deleteDocumentRows/deleteOldVersionRows 语义不变 |
| KnowledgeDocumentMapper/PO | 追加 version 指针更新与 lifecycle 标记方法（既有列，无 DDL） |

### P3 ADMIN 门禁 + 审计（白名单追加式，access 门禁）

| 文件 | 改动 |
|---|---|
| VectorIndexController | index/indexFromPages/delete/reconcile/recover 追加 ObjectProvider requireAdmin + 审计（enabled 时）；status/chunks 既有 checkReadable 不动；响应契约不变 |
| DocumentController | register/import 追加 requireAdmin + 审计；get/list/pages/chunks 既有门禁不动 |
| D40SyncController（P1 新建） | 全部端点 requireAdmin + 审计 |
| application.yml | 仅追加 app.vector-index.fault-injection / sync-retry / audit 段，不改既有行（含注释漂移行） |

门禁模式沿用 D39：`ObjectProvider<AccessPolicyService>.getIfAvailable()`，disabled 时 Bean 不存在→跳过→零差异；审计仅在 enabled 时写（disabled 开发态不写审计，零差异）。

### P4 一致性核查扩展（白名单追加式）

1. VectorReconciliationService 追加：DRIFT_VERSION_MISMATCH（knowledge_document.version ≠ 活动 COMPLETED 版本；PG metadata.version ≠ 关联行 version）、DRIFT_SYNC_STUCK（FAILED 超期/耗尽）；既有 orphan/missing/count/stuck 显式标类 DRIFT_VECTOR_ONLY/DRIFT_META_ONLY/DRIFT_STALE_VERSION（SUPERSEDED 子集）。
2. ReconciliationReportView 追加字段（versionMismatches/syncStuck/driftSummary），既有字段不动。
3. 修复动作全部走管线（H4）：missing→indexChunks 快照重建；orphan→deleteVectorsByIds；version→经管线重切/重索引；stale→sync UPDATE_CLEANUP 重试；syncStuck→手动 retry。**禁裸 SQL 直改业务表**。
4. 一致性核查脚本（temp_docs\D40\）：调 /sync/consistency dryRun 输出四类漂移 + SYNC_STUCK 实测报告；修复后复查归零。

### P5 夹具 + 五类异常矩阵 + 引用交叉验证

1. 夹具（`d40-` 前缀，MySQL 共享表前缀隔离）：4 文档 ×2 片段（d40-doc-a/b/c/d）+ 更新夹具 d40-doc-up（v1→v2）；enabled 时带 D39 权限三键；经 register→import/index 管线入库（非裸插）。
2. 五类异常矩阵（§4.3 E1–E5，受控故障注入 KP3）+ 引用交叉验证（D8）。
3. 清理：PG d40 表夹具行 + MySQL 共享表 d40- 前缀行 + d40_document_sync + d40_admin_audit 夹具行，FIXTURE_REMAINING=0 多处核验；遗留 DOC-* 8 片段与 D39 行零触碰；hybrid 指纹恢复 10fc69d5…。

### P6 回归门禁（夹具前后各一次）

1. access.enabled=false + fault-injection=false：D37 评测器（只读复用）完整跑，质量字段与冻结基线 **60 行逐字节一致**（QUALITY_FIELDS_IDENTICAL）+ QA-001 top1 同 id delta=0 + 匿名行为探针零差异（D39 C05 口径）；既有端点响应契约不变。
2. 夹具清理后：指纹恢复 → 再跑回归 diff 一致 → 资产无损（C19）。
3. enabled 门禁探针：非 ADMIN 写面 4030、ADMIN 通过 + 审计落库（C14/C15）。
4. 不一致 → BASELINE_DRIFT 熔断，停止排查，禁在漂移基线上出报告。

### P7 报告与文档双交付

1. `docs\proj_docs\D40-01_文档更新删除与一致性修复全量信息与操作.md`：全量事实、命令、端点分级、状态机/原因码表、漂移分类、五类异常报告、用例落表、踩坑。
2. `docs\proj_docs\D40-02_文档更新删除与一致性修复技术文档与实践指南.md`：是什么→为什么→底层原理（Outbox + 稳定 UUID v5 + DB Upsert 幂等、MySQL 本地事务边界、先写新再切指针再清旧、tombstone 删除、双端确认、对账补偿、受控故障注入）→**更新/删除流程图（Mermaid graph TD，正序 + 失败分支 + 重试路径，Typora 兼容 subgraph ID [标题] + 节点英文双引号）**→执行流程→核心代码→常见问题→工程实践。
3. 状态文件 `docs\evidence\D40\iteration_records\D40_state_R01.md`（§7 模板）。

---

## §3 工程深度专项

### 3.1 幂等与恢复载荷（既有机制复用，不新造）

- 稳定 UUID v5：name 含 version/strategy/chunkContentHash/splitVersion → 内容/版本/策略变更产生新 ID，旧 ID 不复用；**换 strategy 重索引须先 DELETE 再重建**（D38 教训：upsert 不更新 chunk_id，旧策略行成孤儿）。
- DB 级 Upsert（vectorStore.add）+ chunkMapper.upsertOnNaturalKey：同 ID 冲突即更新，重复执行零重复行。
- Outbox 快照=document_chunk_index.content：恢复唯一载荷来源；无快照（写入前崩溃）→FAILED/NO_SNAPSHOT 待人工，不伪造重建。
- 幂等短路：COMPLETED ∧ payloadHash ∧ 关联数一致→idempotent=true 零写入（重复事件 E4 依据）。
- **幂等证明方法（KP4 冻结）**：前后对比 payloadHash + 排序 chunkId 集合 + PG count + 关联 count，四元组逐字节一致才算「与一次成功完全一致」，禁口头断言。

### 3.2 同步状态机与原因码表（KP4 冻结）

状态迁移：`PENDING → SYNCED`（双端确认通过）；`PENDING → FAILED`（任一步失败 + 原因码）；`FAILED → PENDING`（重试，attempt+1，退避 next_retry_at）；`FAILED ∧ attempt≥maxAttempts → RETRY_EXHAUSTED`（待人工，ADMIN 可重置）。

| 原因码 | 触发 | 归属 |
|---|---|---|
| PG_UNREACHABLE | PG 连接失败（运行期装饰器/坏端口） | DELETE/UPDATE_CLEANUP |
| PG_DELETE_FAILED | PG 删除语句异常（非连接类） | DELETE/UPDATE_CLEANUP |
| CONFIRM_MISMATCH | 双端确认 PG count≠0 或关联行≠0 | DELETE |
| MARK_FAILED | 元数据标记/sync PENDING 事务失败 | DELETE |
| SNAPSHOT_MISSING | 清理时关联行缺但 PG 向量在（转 orphan 管线） | UPDATE_CLEANUP |
| RETRY_EXHAUSTED | 重试达 maxAttempts 仍失败 | DELETE/UPDATE_CLEANUP |
| EMBED_WRITE_FAILED | 向量写入中断（既有，保留） | 索引任务 |
| COUNT_MISMATCH | 写后核对不一致（既有，保留） | 索引任务 |
| NO_SNAPSHOT | 写入前崩溃无 Outbox（既有，保留） | 索引任务 |
| PARSE_FAILED | 解析失败（导入侧 DocumentStatus FAILED，既有语义） | 导入 |

### 3.3 四类漂移 + 同步卡滞（判定与修复，KP4 冻结）

| 漂移类 | 判定 | 修复（走管线 H4） |
|---|---|---|
| DRIFT_META_ONLY（元数据在/向量缺） | 关联行存在 ∧ PG 无对应 id | indexChunks 快照幂等重建 |
| DRIFT_VECTOR_ONLY（向量在/元数据缺） | PG 有 ∧ 无有效关联/任务非活动 | deleteVectorsByIds 补偿删除 |
| DRIFT_VERSION_MISMATCH（版本不一致） | knowledge_document.version ≠ 活动 COMPLETED 版本；或 PG metadata.version ≠ 关联行 version | 经管线重切指针/重索引活动版本 |
| DRIFT_STALE_VERSION（旧版本残留） | SUPERSEDED 任务向量/关联行仍在 PG/MySQL | sync UPDATE_CLEANUP 重试清理 |
| DRIFT_SYNC_STUCK（同步卡滞） | sync FAILED ∧ next_retry_at 超期或 RETRY_EXHAUSTED | 手动 retry 端点（ADMIN） |

### 3.4 事务与跨系统边界

- MySQL 本地事务只收敛元数据标记/版本指针/sync 状态/关联行清理（VectorIndexStateTx 先例）；**PG 调用与 Embedding 绝不纳入本地事务**（避免分布式事务）。
- 顺序不可颠倒：更新=新块先写并核对→SWITCH 原子切指针→清旧（失败不阻断新版本）；删除=标记→清向量→清关联→双端确认。先删旧再写新会导致新版本失败时旧版本全丢（既有注释明示禁止）。
- 双端确认是 SYNCED 的唯一判据：PG count=0 ∧ MySQL 关联行=0，缺一不可；只删 PG 不清 MySQL（或反之）= CONFIRM_MISMATCH。

### 3.5 受控故障注入（KP3 冻结，H5）

- **主手段**：FaultInjectingVectorStore 装饰器——@ConditionalOnProperty fault-injection.enabled=true（默认 false，主 profile 永不装配）包装真实 VectorStore Bean；按调用计数在第 N 次 add/delete 抛受控 RuntimeException（模拟连接丢失/中途失败）；PG 本体真实运行不动，失败点在客户端边界。触发参数（第几次、add/delete）经测试配置预设，可复现。
- **辅手段**：坏端口启动 fail-fast 演练（PGVECTOR_PORT 指向不可达端口，启动即失败，继承 D39 T8）——验证启动期语义，与运行期装饰器互补。
- **禁止**：防火墙改规则、停 PG 服务、pg_terminate_backend、DROP 表、Docker prune/删卷/重建容器、任何影响共享 MySQL/PG 实例的破坏性操作。
- 注入态与回归态隔离：回归（P6）必须在 fault-injection=false 下跑；注入残留装配→熔断。

### 3.6 引用删除交叉验证（D8，D35 链路不回退）

- 确定性路径：夹具入库→构造 EvidenceItem（真实 documentId/chunkId）→真实删除（chunk 行清除）→CitationVerificationService 实库核验→断言 removed 原因=CITATION_TARGET_DELETED（chunkOwners 实查 document_chunk_index，非 mock）。
- tombstone 语义：knowledge_document 行保留（lifecycle DEPRECATED），doc 仍存在但 chunk 行已清→走 chunkId 不在 chunkOwners 分支→CITATION_TARGET_DELETED（而非 doc-null 分支），分类路径须实证。
- **生成中删除竞态**：mock 下不可观测（OneRagOrchestrator 既有注释）→如实 N/A，不伪造端到端；真实生成态留后续。
- 不回退断言：CITATION_OWNERSHIP_MISMATCH/EVIDENCE_NOT_EFFECTIVE 既有分支零改动，UrlSafetyPolicy/引用核验链复用。

### 3.7 继承教训清单（D36–D39 踩坑，实现必须规避）

- Result 包装 HTTP 恒 200：测试以 code 判成败；空检索≠失败；分母 0 标 N/A。
- 多构造候选必须显式 @Autowired（D38 KeywordSearchService 踩中）；装饰器包装 Bean 注意装配歧义（@Primary 或 Qualifier 明确）。
- 换 strategy 重索引先 DELETE 再重建（upsert 不更新 chunk_id）；夹具清理多处核验。
- metadata 列类型 **json（非 jsonb）**（D39 实测）；Filter$Expression record 访问器 type()/left()/right()（单测按访问器断言）。
- MySQL 业务表跨 Dxx 共享：夹具前缀限定清理，禁误删遗留/D39 行；PG 表按 Dxx 隔离（d40_vector_store）。
- delete 端点不联动 knowledge_document 行（D39 C19）：tombstone 裁决后清理脚本须显式处理 lifecycle 标记行。
- javac -encoding UTF-8；Jackson 弃用 API 规避；temp_docs 被 git 忽略，源码提交沿用 git add -f 先例（classes/target/out/surefire-reports 不提交）。
- 运行中掉线瞬间行为 D39 未验证：本轮装饰器补运行期删除失败实证，不再以启动 fail-fast 替代。

---

## §4 验收标准（L1–L5 + C01–C20 + 熔断）

### 4.1 分级定义

| 级 | 内容 | 本轮要求 |
|---|---|---|
| L1 | 编译/新建单测/前端零回归 | 必须 PASS |
| L2 | 探测门禁/seeding 指纹/DDL 幂等/门禁零差异 | 必须 PASS |
| L3 | 更新删除闭环/五类异常/一致性核查/引用交叉/回归无损 | 必须 PASS |
| L4 | ADMIN 门禁/审计/凭据卫生/预算/清理 | 必须 PASS |
| L5 | 跨机复现 | NOT RUN（单机），如实标注 |

四态逐用例落表，计数合计=用例总数；N/A 须写原因。

### 4.2 逐条用例（证据路径相对 `docs\evidence\D40\`）

| 编号 | 用例 | PASS 判据 | 证据 |
|---|---|---|---|
| C01 | 编译+单测+前端 | mvn test 退出码 0；sync 状态机/原因码/幂等指纹/门禁/审计单测全绿；node --test 前端 6/6 零回归 | compile-test.txt |
| C02 | KP1–KP4 探测 | 四门禁裁决留痕；endpoint-inventory.csv 写面全分级；sync-params.json 冻结 | probe\ |
| C03 | DDL 幂等 | d40_document_sync + d40_admin_audit ×2 重跑零报错零数据变化 | ddl\ |
| C04 | seeding+指纹 | d40_vector_store=8 片段；hybrid 指纹=10fc69d5… 逐字节一致；遗留行清单冻结 | probe\kp2-* |
| C05 | 门禁零差异回归 | enabled=false：D37 质量字段 60/60 字节一致 + QA-001 delta=0 + 匿名探针零差异；端点契约不变 | regression\ |
| C06 | 更新闭环 v1→v2 | 新块先写→SWITCH 原子切指针（knowledge_document.version 更新）→旧版清理；顺序实证 | update\flow-v1v2.json |
| C07 | 更新重入幂等 | 中途失败→重跑→payloadHash+chunkId 集+PG 数+关联数四元组与一次成功逐字节一致 | update\reentry-fingerprint.json |
| C08 | 删除闭环 | 标记 DEPRECATED→PENDING→清向量→清关联→双端确认→SYNCED；tombstone 保留 | delete\closure.json |
| C09 | 删除失败重试 | 注入 PG 失败→FAILED+PG_UNREACHABLE 落库→恢复重试→SYNCED，双端计数 0/0 | delete\retry-converge.json |
| C10 | 重复事件幂等 | 同删除/更新提交两次→索引与状态不变（idempotent 短路/SYNCED no-op） | delete\duplicate-event.json |
| C11 | 清理失败同步 | cleanupSuperseded 注入失败→UPDATE_CLEANUP FAILED→重试→SYNCED，残留归零 | update\cleanup-sync.json |
| C12 | 一致性四类+卡滞检测 | 构造漂移→核查脚本输出 DRIFT_META_ONLY/VECTOR_ONLY/VERSION_MISMATCH/STALE_VERSION/SYNC_STUCK 全命中 | consistency\detect-report.json |
| C13 | 漂移修复走管线 | 修复→复查归零；grep 证明无破坏性裸 SQL 直改业务表 | consistency\repair-recheck.json |
| C14 | ADMIN 门禁 | enabled：非 ADMIN 写面 4030、ADMIN 通过；disabled：零差异（匿名探针） | security\gating.jsonl |
| C15 | 审计留痕 | 特权操作落 d40_admin_audit（actor/action/target/result/request_id 齐全） | security\audit-trail.json |
| C16 | E1 解析失败 | 畸形导入→DocumentStatus FAILED、不进向量库、无残留（PG delta=0） | exceptions\e1-parse.json |
| C17 | 引用交叉验证 | 删除后 CitationVerificationService 实库核验 removed=CITATION_TARGET_DELETED；mock 竞态 N/A 留痕 | exceptions\citation-deleted.json |
| C18 | 故障注入收口 | fault-injection 默认 false、主 profile 永不装配、回归态无注入残留 | exceptions\injection-containment.json |
| C19 | 清理+回归复验 | FIXTURE_REMAINING=0（PG/MySQL 共享表/sync/audit 多处）；遗留+D39 行零触碰；指纹恢复；回归 diff 再一致 | cleanup\ + regression\ |
| C20 | 卫生+预算+文档+Git | token/sk-/Bearer 扫描零命中；DeepSeek 0/百炼 0（mock+one_ 关）；D40-01/02+状态文件齐全；提交范围核验不 push | security\ + cost\ + git\ |

### 4.3 五类异常矩阵（每类含故障注入方式 + 收敛证据，受控手段 H5）

| 编号 | 场景 | 注入方式 | 验收标准 |
|---|---|---|---|
| E1 | 解析失败 | 畸形文件（非法 PDF/空内容）经 import 管线 | 不进向量库、DocumentStatus FAILED、无残留（PG/关联 delta=0） |
| E2 | 部分入库 | 装饰器第 N 次 add 抛异常（中途终止） | PARTIAL/EMBED_WRITE_FAILED 落库→关闭注入重跑→四元组指纹与一次成功完全一致（幂等证明） |
| E3 | 删除重试 | 装饰器 delete 抛 PG_UNREACHABLE | FAILED 落库→恢复后手动/自动重试→SYNCED，双端计数一致 0/0 |
| E4 | 重复事件 | 同一删除/更新事件提交两次 | 索引与状态不变（幂等短路/SYNCED no-op），attempt 不无谓增长 |
| E5 | 旧版本残留 | 装饰器 cleanupSuperseded 阶段 delete 失败 | 构造残留→核查脚本发现 DRIFT_STALE_VERSION→sync 重试修复→复查归零 |
| 交叉 | 引用删除 | 真实删除夹具 chunk 行 | CitationVerificationService 命中 CITATION_TARGET_DELETED（D35 不回退）；mock 竞态 N/A |

### 4.4 熔断条件（命中即停，写状态文件后报告，不得绕过）

1. 同一根因连续失败 2 次 → 停止该链路，持久化现场与原因码，等待人工确认。
2. KP2 指纹漂移 / P6 回归不一致 → 停止，先 seeding/修资产，禁带漂移推进。
3. 夹具清理误触遗留 DOC-* 或 D39 行 / FIXTURE_REMAINING≠0 → 立即停止，前缀限定核查。
4. 需要破坏性操作（DROP/prune/防火墙/停 PG/裸 SQL 直改业务表）才能推进 → 停止请示（H4/H5）。
5. enabled 时特权写面非 ADMIN 可访问 / 审计缺失 → 安全缺陷，立即停止该面，修复后全矩阵重跑。
6. fault-injection 在主 profile 装配 / 残留进回归 → 立即停止，隔离注入态。
7. 远程调用计数 >0（本轮结构性零远程）→ BUDGET_EXCEEDED，命中即实现违约。

---

## §5 成本与预算（不伪称预算）

| 项 | 规则 |
|---|---|
| 全部测试 | local Embedding 零网络；生成走 mock（ai.mode=mock 默认）；one_ 默认关（dims=0）→ **DeepSeek 0 调用、百炼 0 调用、Token 0**，以模式配置 + one_ enabled=false + 零远程计数三重证明 |
| 累计 Token 阈值模板 | 300 万提示 / 400 万暂停复核 / 500 万硬停止（本轮预期恒 0，模板保留供后续生成态迭代） |
| 计算资源 | 夹具 4 文档 ×2 片段 + 更新 v2 量级极小；sync/audit 表行数可控；对账全表扫描 8+夹具行毫秒级；回归与测试同口径计时 |
| 凭据管理 | PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY 仅环境变量（只验存在不回显）；MYSQL_PASSWORD 本机默认如实记录；夹具 token 明文仅 temp_docs\D40\auth\（不提交），DB 存 SHA-256 |

---

## §6 交付物清单与 Git 规则

| # | 交付物 | 路径 |
|---|---|---|
| 1 | 同步闭环代码（sync 新包 + 白名单追加改动 + 门禁审计） | com.zl.d40.document.sync 全套 + §2 白名单 |
| 2 | 幂等 DDL + 凭据种子 + 夹具 + seeding + 一致性核查脚本 | docs\temp_docs\D40\（保留不删） |
| 3 | 探测与参数冻结 + 端点分级 + 状态机/原因码/漂移冻结 | docs\evidence\D40\probe\ |
| 4 | 更新/删除流程图 + 状态机定义 + 原因码表 | D40-02 文档内（Mermaid）+ probe\ 冻结 |
| 5 | 一致性核查实测输出 + 修复复查 | docs\evidence\D40\consistency\ |
| 6 | 五类异常测试报告（注入方式 + 收敛证据） | docs\evidence\D40\exceptions\ |
| 7 | 更新/删除/重试/门禁/审计证据 | docs\evidence\D40\{update,delete,security}\ |
| 8 | 回归/清理/DDL 证据 | docs\evidence\D40\{regression,cleanup,ddl}\ |
| 9 | 两份技术文档 | docs\proj_docs\D40-01_…全量信息与操作.md、D40-02_…技术文档与实践指南.md |
| 10 | 迭代状态文件 | docs\evidence\D40\iteration_records\D40_state_R01.md |
| 11 | 运行日志 | logs\D40\（surefire-reports 禁提交） |

Git：迭代提交仅含新源码、白名单改动、DDL/脚本**源码**（temp_docs 被忽略，git add -f 先例，夹具 token JSON 不提交）、evidence\D40、proj_docs 两份、状态文件；D37/D38/D39 资产与 MySQL 遗留行零改动；提交前 `git diff --cached --stat` 核验；提交信息 `D40 项目迭代R01 文档更新删除与一致性修复`；不 push。临时文件一律不自动删除。

---

## §7 状态文件模板（D40_state_R01.md）

```markdown
# D40_state_R01 —— 文档更新删除与一致性修复（one 仓）
- 迭代/切片：D40 / R01；基线 commit：f3cf475（执行时复核）；本切片交付 commit：<git>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17
- 冻结参数：syncTable=d40_document_sync auditTable=d40_admin_audit baselineFingerprint=10fc69d5… datasetHash=75cc8d83cfb2… faultInjectionGate=<KP3> fingerprintMethod=<KP4>
- 偏差裁决：D1 操作级 sync 表 / D2 tombstone 删除 / D3 版本指针权威化 / D4 清理失败落库 / D5 四类漂移+SYNC_STUCK+seeding / D6 写面收口范围 / D7 受控注入 / D8 引用确定性核验+竞态 N/A / D9 骨架已提交+新建单测（执行时确认）

## 门禁与用例落表
| 编号 | 状态(PASS/FAIL/BLOCKED/NOT RUN/N/A) | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | … | probe\… | … |
| C01–C20 | … | … | … |
| E1–E5+交叉 | … | exceptions\… | 注入方式+收敛证据 |

## 分级结论
L1=… L2=… L3=… L4=… L5=NOT RUN（单机）

## 一致性与同步结论
- 四类漂移 + SYNC_STUCK 检测/修复/复查归零；sync 状态机迁移实证；幂等四元组指纹对比
- 删除双端确认 0/0；tombstone 保留；CITATION_TARGET_DELETED 命中（竞态 N/A 留痕）

## 成本实耗
DeepSeek calls=0 tokens=0（mock）；百炼 calls=0（one_ 关）；夹具清理 FIXTURE_REMAINING=0；指纹恢复基线

## 诚实声明
- 已执行并通过：… / 已执行但失败：… / 尚未执行：… / N/A 项及原因：…

## 遗留与 D41 候选
Embedding/History/PromptTemplate 写面收口 / 文档硬删除与生命周期迁移 / one_ 真实端到端 / SSE 全路径 / 真实生成态删除竞态 / Spring Security 升级 / 跨机 L5
```

---

## §8 官方文档溯源（执行时复核，不锁定记忆值）

- 《Spring AI Reference — Vector Databases / VectorStore | Spring AI 参考-向量数据库与 VectorStore》：https://docs.spring.io/spring-ai/reference/api/vectordbs.html （add/delete 契约与 DB 级 Upsert 语义，幂等恢复依据）
- 《PostgreSQL Documentation — DELETE | PostgreSQL DELETE 语句》：https://www.postgresql.org/docs/current/sql-delete.html （对不存在行删除不报错，重试安全依据）
- 《MySQL 8.0 Reference Manual — START TRANSACTION, COMMIT, and ROLLBACK Statements | MySQL 事务语句》：https://dev.mysql.com/doc/refman/8.0/en/commit.html （本地事务边界，跨系统不入事务依据）
- 《RFC 4122 — A Universally Unique IDentifier (UUID) URN Namespace | RFC 4122 UUID 命名空间》：https://www.rfc-editor.org/rfc/rfc4122 （UUID v5 名称型稳定 ID 依据）
- 《pgvector — Open-source vector similarity search for Postgres | pgvector 官方仓库》：https://github.com/pgvector/pgvector （WHERE 谓词与向量删除口径）
- 本地权威依据：D37 冻结基线/数据集/评测器（docs\evidence\D37\）、D39 R01 状态与 access 冻结参数（docs\evidence\D39\iteration_records\D39_state_R01.md、probe\access-params.json）、D39 seeding/凭据种子脚本（docs\temp_docs\D39\）、D40 骨架实扫（§1.1）。

> 溯源纪律：不得伪造文档地址/源码内容/测试结果；无法确认的信息标「待运行时核验」。本任务书所有实测值来自编制时真实扫描，执行时若漂移，以执行时实读为准并在状态文件记录差异。