# D42 项目迭代任务书 Prompt（R01）：Rerank 价值评估（one 仓）

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 证据生文档。沿用 D34–D41 体裁。
> 核心原则：**Rerank 是可插拔的检索后精排实验分支，默认关闭、fail-open 绝不阻断检索；用同一冻结数据集量化 rerank 前后的相关性/耗时/费用/异常；采用与否由预先注册的决策门禁裁决，禁止看结果后调阈值；收益不明确则不保留为默认路径。**
> 编制日期：2026-11-25。编制依据：对 `E:\AI\project\AI-Learning-one\M3-Java\D42` 骨架（HEAD `a086fa2`=D42 基础骨架**已提交**、工作树干净；D41 已执行 `b76c106`+修复 `b479680`+断言 `af2704f`）与 D37/D40/D41 冻结资产的真实扫描（§1），非记忆假设。
> 硬约束：以本地实际代码为唯一依据；白名单内最小必要修改；不伪造指标/费用/对账；空检索≠检索失败；核心契约禁用无约束 Map；保留熔断与 Token 阈值模板；**远程 rerank 须经显式环境变量门禁与预算上限，缺省零远程**。

---

## §0 迭代总览与硬契约

### 0.1 目标

在 D42 模块（`D42\D42_RERANK`，包 `com.zl.d42`，端口 **8080**）对既有 Top-K 向量检索结果建立**可插拔 rerank 实验分支**（新包 `com.zl.d42.rag.rerank`，端口适配器模式对齐 `ChatModelPort`）：本地零成本词法精排 + 可选远程 DashScope `gte-rerank-v2` 精排；在 D37 冻结数据集（60 题）上比较 rerank 前后**相关性（含新增排序敏感指标）、耗时、调用费用、异常**；产出重排实验记录与**采用/不采用决策**；只有收益明确才保留为可选路径（默认仍关闭）。

### 0.2 硬契约（违反任意一条即任务失败）

| 编号 | 契约 | 说明 |
|---|---|---|
| H1 | 白名单最小修改 + 零差异 | 既有文件改动仅限 §2 白名单，全部追加式。**rerank 不修改检索谓词/权限代数/切分/既有页文本**；`rerank.enabled=false`（默认）时全套 Bean 不装配、链路与引入前逐字节一致。回归用 **D40 C05 口径**：D37 冻结基线 60 行 **QUALITY_FIELDS_IDENTICAL + SelfTest 75/75 + 匿名探针零差异**（disabled 态全列 diff=0；enabled 态语义见 D2/C05）。白名单外既有文件零改动。 |
| H2 | 新开关默认关闭 | `app.ai.rag.rerank.enabled` 默认 **false**（无 matchIfMissing）；`app.ai.rag.rerank.remote.enabled` 默认 **false**（远程臂须显式 `RERANK_REMOTE_ENABLED=true`+`DASHSCOPE_API_KEY` 注入）；既有 access/hybrid/one_/table/fault-injection/sync-retry 默认值**一律不动**。 |
| H3 | 不引入新框架 | 远程精排**只用既有 dashscope-sdk-java 2.23.6 的 TextReRank API**（javap 实证在 jar）；本地精排只用 JDK + 既有分词能力；**禁止引入 cohere/rerank 新 starter、外部 rerank 服务或新依赖**。需新框架才能推进 → 熔断请示。 |
| H4 | 指标与费用真实 | 相关性/耗时/异常全部真实执行留痕；**费用=实测 totalTokens×官方单价**（远程臂）或显式标「估算/NOT RUN」（零远程时）；不伪造收益；排序指标由有序 returnedLogicalIds + 数据集 expectedChunkIds 离线复算，口径冻结后禁改。 |
| H5 | 决策门禁预注册 | 采用/不采用阈值（ΔMRR/ΔnDCG/hit@1/延迟/异常率/费用上限）**KP4 先冻结后实验**，禁止看结果后调整（EVAL_CONTROLLED_BASELINE 纪律延伸）；决策记录含每条阈值实测对照与裁决理由。 |
| H6 | 冻结资产只读 + 可清理 | D37 数据集（SHA `75cc8d83cfb2…`）/基线（baseline.csv 60 行）/评测器源码、D40/D41 证据只读；**不改冻结评测器**，排序指标用新增独立脚本；实验夹具/语料经既有 seeding 管线，PG **d42_vector_store**（initialize=true 新表预计 0 行）；MySQL 业务表跨 Dxx 共享，遗留 DOC-* 3 文档 8 片段 + chunk_other 32 行零触碰；清理前缀限定 + FIXTURE_REMAINING=0；hybrid 指纹恢复 `10fc69d5…`。 |
| H7 | 权限与安全同口径 | rerank 作用于**已经权限过滤**的检索结果（RagRetrievalService 预门禁之后），绝不 bypass D39 谓词；远程 API Key 仅环境变量；R01 **不新增写面端点**（配置经 env/yml），不开出未鉴权实验面；实验记录落证据文件不落公开端点。 |
| H8 | 诚实记录 + fail-open | 四态 PASS/FAIL/BLOCKED/NOT RUN 逐用例落表；远程臂未授权/无 Key → **NOT RUN** 如实标注，决策仅基于已执行臂；rerank 任何失败**回退原始向量序**并标 RERANK_FAILED_OPEN，绝不把检索打成 FAILED；空候选→noop；分母 0 标 N/A。 |
| H9 | 凭据与预算卫生 | 密钥仅环境变量（DASHSCOPE_API_KEY/PG_VECTOR_PASSWORD/AI_API_KEY）；日志/证据/Git 对 sk-/Bearer 实值扫描零命中；surefire-reports/target/评测器 out 禁提交；temp_docs 不自动删除；不 push。 |

### 0.3 范围边界（本轮 R01 明确不做）

- 新 rerank 框架/外部 rerank 服务/新依赖：不引入（H3）。
- hybrid（BM25+RRF）与 rerank 的组合实验：hybrid 默认关，组合臂列 D43 候选（本轮 rerank 只作用于纯向量 Top-K 基线）。
- 修改冻结评测器/数据集/基线：禁止（排序指标走新增脚本）。
- rerank 模型微调/训练、cross-encoder 本地模型：不做。
- 新增实验管理写面端点/DB 实验表：R01 实验记录走证据文件（JSONL/CSV），不建表不开端点。
- D41 遗留文档缺口（proj_docs 无 D41-01/02、evidence\D41\iteration_records 为空）：实扫观察到的事实，**非本轮修复范围**，记录于状态文件遗留项。
- 跨机 L5、SSE 全路径、one_ 真实轨：NOT RUN。

### 0.4 与用户原始需求的十处已裁决偏差/细化（执行前必读，均有实扫依据）

| # | 用户原文 | 实测事实 | 裁决 |
|---|---|---|---|
| D1 | 「对已有 Top-K 结果做可插拔的 rerank 实验」 | 检索链：RagController→RagOrchestrator（**L77 retrieve→L83 FAILED 分支→上下文组装→L100 assess→生成**）；RagRetrievalService 预门禁 requireCurrent 在 try 外（fail-closed 4030）、EMPTY≠FAILED、委托 SimilaritySearchService（VectorStore.similaritySearch topK+similarityThreshold，真实分数映射，权限谓词单一代数源）；**全仓 grep rerank 零命中**；adapter 包已有 ChatModelPort 端口适配器范式 | 新包 `com.zl.d42.rag.rerank`：**RerankPort 接口** + NoopRerankAdapter（恒等，默认）+ LocalLexicalRerankAdapter（零成本确定性词法精排）+ DashScopeRerankAdapter（env 门禁）；挂点=**RagOrchestrator retrieve SUCCESS 之后、上下文组装之前**追加式精排步（白名单）；RagRetrievalService/权限代数零触碰；rerank 只重排已过滤结果 |
| D2 | 「同固定数据集比较 rerank 前后相关性」 | **D37 MetricsCalculator 全部集合型指标**（docHit/singleRecall 用 Collection/precision 用计数/hitRate），**无 MRR/nDCG 等排序敏感指标**；baseline.csv 16 列含 **returnedLogicalIds（有序，String.join(";")）**+labels+status；数据集每题有 expectedChunkIds/expectedDocumentIds | **关键洞察：纯重排同一 Top-K 集合 → 集合型 recall/precision/docHit/noise 结构性不变**；收益必须用**新增排序敏感指标**（MRR@5、nDCG@5 二值相关、hit@1、首个相关命中位次）由有序 returnedLogicalIds + expectedChunkIds 离线复算（新脚本 d42_rank-metrics，**不改冻结评测器**）。**C05 双态语义**：disabled 全列 diff=0；enabled 集合型列 diff=0 而 returnedLogicalIds 允许变化（该变化即实验信号，落实验记录非回归失败） |
| D3 | 「只有收益明确才保留为可选路径」 | 无既有决策门禁机制；D37 EVAL_CONTROLLED_BASELINE 纪律（参数显式传入、噪声阈值 0.5 正式运行前冻结禁看结果调整） | **决策门禁 KP4 预注册**（ΔMRR@5≥+0.05 且 ΔnDCG@5≥+0.05 且 hit@1 不降 且 p95 附加延迟≤阈值 且 异常率≤1% 且 远程费用≤上限 → 保留为可选；否则不采用）；**无论采用与否 enabled 默认 false**（可选 opt-in）；阈值冻结后禁改 |
| D4 | 「比较调用费用」 | dashscope-sdk-java 2.23.6 在模块 pom；**javap 实证 jar 含完整 rerank API**：TextReRank.call(TextReRankParam)、TextReRankParam{query/documents/topN/returnDocuments/instruct}、Models.GTE_RERANK_V2、TextReRankUsage.getTotalTokens、异常 ApiException/NoApiKeyException/InputRequiredException；**官方单价 gte-rerank-v2=0.8 元/百万 tokens**（0.0008 元/千 token），限 500 docs/单条 4000 token/请求 30000 token/RPM 5040 | 费用=**实测 totalTokens×0.8/1e6 元**（远程臂逐 call 落 usage）；本地臂费用恒 0；零远程时费用标「估算（公式）/NOT RUN」不伪称实测；远程臂预算上限 KP4 冻结（call 数/token/元三重帽） |
| D5 | 「比较异常情况」 | resilience 包：ModelExceptionClassifier/ModelErrorCategory/ModelResilienceSupport；M3 韧性约定：RetryTemplate maxAttempts=1 关闭内置重试、重试收归应用层、仅瞬时错误可重试、鉴权/参数/余额永久错误不重试 | rerank 异常分类矩阵 E1–E5（§4.3）：鉴权/参数/配额=永久不重试；超时/瞬时=应用层单次重试后回退；**任何失败 fail-open 回退原始向量序 + RERANK_FAILED_OPEN 标记**，绝不阻断检索；空候选 noop；单条超 4000 token 截断 + TRUNCATED 标记；请求总 token≤30000 预检 |
| D6 | 「重排实验记录」 | 无实验记录表；D37 评测器输出 runDir（raw-results.jsonl/baseline.csv/summary）；evidence 目录范式成熟 | 实验记录=**证据文件**：evidence\D42\rerank\runs\<arm>\*.jsonl（每题 orderBefore/orderAfter/scores/latencyMs/tokens/cost/exception/flags）+ summary.csv + decision.md；**不建 MySQL 实验表、不开端点**（最小必要） |
| D7 | 「耗时比较」 | RagTimingView/RagBudgetView 既有；评测器 warmup=3 rounds=3 | 耗时口径 KP4 冻结：rerank 阶段附加延迟（p50/p95/max，逐题计时）+ 端到端延迟对照；warmup 排除；本地/远程分臂统计；计时用 System.nanoTime，落 jsonl |
| D8 | 「同固定数据集」 | D37 数据集 60 题 SHA 75cc8d83cfb2…；评测器配置 topK=5/threshold=0.0/warmup=3/rounds=3/baseUrl 8080/noiseThreshold 0.5；corpus=equipment-fault-corpus.json（3 文档 8 片段）；hybrid 指纹期望 10fc69d5… | 实验全程**同一冻结数据集+同一语料+同一检索参数**（topK=5 threshold=0.0）；臂间唯一变量=rerank 策略；seeding 经 d41_index-corpus.ps1 模式入 d42_vector_store→指纹须=10fc69d5…；可选截断臂 A3（rerank→top3）单独标注集合变化 |
| D9 | 「D42 基础骨架仓库」 | HEAD a086fa2 **骨架已提交**、工作树干净（**与 D41 编制时未提交不同，P0 无需先提交骨架**）；**D42 src/test 仅前端 6 用例 + application-test.yml，零 Java 测试**；D41 模块有 **15 个测试文件**（10 sync + RecoverStuckTasksScope + PdfTableFixtureTruthTest/TableCellTypingTest/TableRecordRebuildTest/TextTableExtractorTest + D41TableFixtureGenerator）；proj_docs 有 D42-知识笔记-GPT-…（**D42-01/02 编号可用**） | **C01 须先迁移 D41 15 测试文件（d41→d42 包名/表名 d42_ 适配）作继承基线** + 新增 rerank 单测 + 前端 6/6；evidence/D42 与 temp_docs/D42 不存在需新建；logs/D42 已存在 |
| D10 | 「可插拔」 | ChatModelPort 范式：接口+适配器+Configuration+UsageReader；ObjectProvider 可选依赖门禁模式（access/table 先例） | RerankPort 契约：`RerankResult rerank(String query, List<RerankCandidate> candidates, int topN)`，RerankResult 含有序结果+分数+usage+timing+flags（**类型化 record，禁无约束 Map**）；ObjectProvider<RerankPort> 注入 orchestrator，disabled Bean 缺席=零差异；适配器选择经配置枚举（noop/local/remote），远程适配器独立条件装配 |

---

## §1 基线核实结果（编制时真实扫描，执行时须复核）

### 1.1 已核实事实（证据均为实读/实测）

| 项 | 实测值 |
|---|---|
| Git 基线 | HEAD `a086fa2`（D42 项目基础骨架，**已提交**，工作树干净）；前序 `254b316`（D42 知识笔记）、`b479680`（fix D41：重建时续表 id 重映射 + 表格专项测试 + 真实手册逐格审计）、`b76c106`（D41 迭代）、`af2704f`（D41 测试断言）、`459e6a3`（D41 骨架）、`aff5441`/`2f2af95`（D40） |
| 模块 | `D42\D42_RERANK`，包 `com.zl.d42`，端口 **8080**；main Java **340 个**；顶层包 access/adapter/common/config/controller/conversation/device/document/dto/history/hybrid/knowledge/logging/observability/one/prompt/rag/resilience/service |
| 技术基线 | Spring Boot 3.5.16 / JDK21 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17 / Spring Cloud 2025.0.0 / SCA 2025.0.0.0 / **dashscope-sdk-java 2.23.6**（模块 pom 显式）/ openai-java / mysql-connector 8.0.33 / PDFBox 3.0.7 / Tika reader |
| rerank 现状 | **全仓 grep rerank/Rerank/RERANK 零命中**；无精排逻辑 |
| DashScope rerank API（javap 实证） | jar 含 `com.alibaba.dashscope.rerank.*`：**TextReRank.call(TextReRankParam)** throws ApiException/NoApiKeyException/InputRequiredException；TextReRankParam.builder(){query, documents(List<String>), topN(Integer), returnDocuments(Boolean), instruct(String)}；**Models.GTE_RERANK_V2**；TextReRankUsage.getTotalTokens()；TextReRankOutput$Result/$Document |
| 官方价格与限制（已查证） | **gte-rerank-v2：0.8 元/百万 tokens（0.0008 元/千 token）**；最大 500 documents、单条≤4000 token、请求≤30000 token、RPM 5040（来源见 §8） |
| 检索链 | RagOrchestrator：**L77 retrievalService.retrieve → L83 FAILED 分支 → 上下文组装 → L100 evidenceAssessmentService.assess → 生成**；RagRetrievalService：ObjectProvider<AccessPolicyService> 预门禁 requireCurrent（try 外，fail-closed 4030）、EMPTY/FAILED/SUCCESS 三态、委托 SimilaritySearchService；SimilaritySearchService：VectorStore.similaritySearch（topK+similarityThreshold），真实分数映射不自行归一化，权限谓词单一代数源 |
| 端口适配器范式 | adapter 包：ChatModelPort/DeepSeekChatAdapter/DeepSeekChatModelConfiguration/NativeUsage/NativeUsageReader/DeepSeekNativeUsageReader |
| 韧性 | resilience 包：ModelClientConfiguration/ModelErrorCategory/ModelExceptionClassifier/ModelInvocationException/ModelResilienceProperties/ModelResilienceSupport；约定 maxAttempts=1 关内置重试、应用层单一重试层、永久错误不重试 |
| hybrid | HybridProperties/RrfFusionService/KeywordSearchService/InvertedIndex/KeywordTokenizer/HybridSearchService；默认关；BM25 k1=1.2 b=0.75、rrf-k=60、candidate-top-n=10、feature-subset [w,g]；**指纹门禁 expected=10fc69d5c9c3fd088b98578d74278135b1c04b7cd8b29b361b4c983926567af9** |
| D41 继承 | document.table 全套已在；yml `app.ai.document.table.enabled=${DOCUMENT_TABLE_ENABLED:true}`、ocr.enabled 恒 false；D41 修复 b479680（续表 id 重映射）已在代码 |
| 存储 | PG **d42_vector_store**（PGVECTOR_TABLE 默认 d42_vector_store，initialize=true，384 维，新表预计 0 行）+ **one_d42_vector_store**（one_ 默认关 dims0）；MySQL 共享 knowledge_document/knowledge_document_page/document_chunk_index/document_vector_index/d42_document_sync/d42_admin_audit/d42_auth_principal；遗留 DOC-* 3 文档 8 片段 + chunk_other 32 行 |
| 配置 | access 默认 false、deny-code **4030**、sentinel "*"；fault-injection/sync-retry 默认 false；AI_MODE=mock；embedding local-hashing 384/COSINE；chunk 500/100 |
| 评测器 | **temp_docs\D37\evaluator\src\d37eval\** 9 源（Backends/Corpus/Dataset/EnvProbe/Evaluator/FailureLabels/LocalEmbedding/MetricsCalculator/SelfTest）；**MetricsCalculator 全集合型**（docHit/singleRecall(Collection)/precision(计数)/hitRate），无排序指标；Evaluator qualityProjection 含 returnedLogicalIds（有序）/labels/status，fail>0→exit 1；**SelfTest 75 断言**（D40 权威口径，grep check( 76 含定义行）；evaluator-config：topK=5/threshold=0.0/warmup=3/rounds=3/baseUrl 8080/noisePrecisionThreshold=0.5（EVAL_CONTROLLED_BASELINE 冻结纪律） |
| 冻结资产 | D37 数据集 rag-evaluation-questions.v1.0.jsonl **60 题** SHA `75cc8d83cfb2…`（每题 expectedChunkIds/expectedDocumentIds/scenario/expectedDecision）；基线 baseline\v1.0\PGVECTOR\baseline.csv **60 行 16 列**（questionId,scenario,retrievalBackend,expectedDecision,actualDecision,retrievalStatus,generationStatus,errorCode,hitCount,relevantCount,docHit,recall,precision,noise,returnedLogicalIds,labels,status） |
| 测试现状 | **D42 src/test 仅 front-end-safety.test.js（6 用例）+ application-test.yml，零 Java 测试**；D41 模块 **15 测试文件**（迁移源）；D40 权威回归口径 QUALITY_FIELDS_IDENTICAL 60/60 + SelfTest 75/75 + runExit=1=冻结基线已知业务 FAIL 契约 |
| 可复用脚本 | temp_docs\D37\run-evaluation.ps1/index-corpus.ps1/freeze-dataset.ps1/special-fixtures.ps1；temp_docs\D40\d40_c05-regression.ps1/d40_index-corpus.ps1/d40_p6_cleanup.ps1；**temp_docs\D41\d41_index-corpus.ps1**（seeding 先例）/d41_p2_smoke.ps1/d41_r03_online_verify.ps1 |
| D41 执行证据观察 | evidence\D41 含 R01（cell-diff/extracted-cells/source-cells/source-manual/online-verify/error-list）及 capability/cleanup/cost/ddl/exceptions/extract/index/linkage/p2/probe/regression/security/sync 子目录；**iteration_records 目录为空（状态文件缺失）、proj_docs 无 D41-01/02**——实扫观察到的遗留缺口，记录非本轮修复 |
| 目录 | docs\evidence\D42 与 docs\temp_docs\D42 编制时**不存在**（本轮新建）；logs\D42 已存在；proj_docs 有 D42-知识笔记-GPT-RAG Reranking 重排序机制与工程实践.md（**D42-01/02 编号可用**） |
| 工具链 | Maven `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`（JDK21 `D:\SoftwareInstall\JDK\JDK21`，仓库 D:\SoftwareInstall\Apache\repository，勿传 -Dmaven.repo.local）；PowerShell 7；`[Console]::OutputEncoding=UTF8`；无 mysql CLI（jshell/JDBC 先例）；javap 可实证 jar API |

### 1.2 执行时复核命令（PowerShell 7，逐条留痕）

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-one\M3-Java"
git -C $r rev-parse HEAD; git -C $r status --porcelain   # 须 a086fa2 且干净
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "$r\D42\pom.xml" -pl D42_RERANK -q compile
& "D:\SoftwareInstall\JDK\JDK21\bin\javap.exe" -cp "D:\SoftwareInstall\Apache\repository\com\alibaba\dashscope-sdk-java\2.23.6\dashscope-sdk-java-2.23.6.jar" com.alibaba.dashscope.rerank.TextReRank  # 须 call(TextReRankParam)
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
(Get-FileHash "$r\docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl" -Algorithm SHA256).Hash  # 须 75cc8d83cfb2 开头
# 应用起来后基线端点（端口 8080；Result.code 判成败，HTTP 恒 200；拒绝=4030）
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/documents/DOC-CNC-COOLING-001/pages" -TimeoutSec 5 | ConvertTo-Json -Depth 4
```

### 1.3 探测门禁（P0 裁决，证据落 `docs\evidence\D42\probe\`）

| 门禁 | 待核实项 | 方法 | 通过判据 | 失败处置 |
|---|---|---|---|---|
| KP1 | 能力与挂点 | javap 复核 TextReRank API；RagOrchestrator 挂点行号复核；RerankPort 契约设计；端点盘点（确认 R01 不新增写面）；本地分词能力盘点 | API 签名实证；挂点唯一；契约类型化；零新端点 | API 不符→以 jar 实证为准修契约；禁引新框架（H3） |
| KP2 | 存储盘点 + seeding | JDBC 只读盘点 d42_vector_store（预计 0）、MySQL 共享表前缀分布、遗留 DOC-*/chunk_other 清单；**seeding 经 d41_index-corpus.ps1 模式**入 8 片段→指纹须=10fc69d5… | d42 表=8 片段、指纹逐字节一致；遗留清单冻结 | 0 行→先 seeding；指纹不等→CORPUS_DRIFT 熔断 |
| KP3 | 冻结资产核验 | 数据集 SHA/基线 60 行 16 列/评测器 9 源全量 javac 重编自检（SelfTest 75）；排序指标脚本输入口径（有序 returnedLogicalIds + expectedChunkIds）确认 | 资产无损；评测器可重编；排序指标可复算 | SHA 漂移→停止；评测器编译失败→修环境不改源码 |
| KP4 | 实验协议与决策门禁冻结 | 冻结臂定义（A0/A1/A2/A3）、检索参数（topK=5/threshold=0.0/warmup3/rounds3）、排序指标公式（MRR@5/nDCG@5 二值/hit@1/首个相关位次）、**决策阈值**、延迟口径、异常分类码表、远程预算三重帽（call≤400/token≤2M/费用≤¥5）、截断规则（4000/30000） | 协议文件 rerank-protocol.json 生成，实验/裁决一律引用；**阈值先于结果冻结** | 协议冲突→先裁决冻结，禁实验期私改 |

---

## §2 分阶段实施要求

### P0 探测与协议冻结（骨架已提交，无需先提交）

KP1–KP4 裁决留痕；seeding 使 d42_vector_store=8 片段、指纹=10fc69d5…；冻结 `probe\rerank-protocol.json`：`{arms, retrievalParams, rankMetricFormulas, decisionThresholds, latencyProtocol, exceptionCodes, remoteBudget{maxCalls,maxTokens,maxCny}, truncationLimits, datasetHash, baselineFingerprint, legacyDocIds, skeletonHead, probedAt}`。

### P1 可插拔 rerank 分支（新包 `com.zl.d42.rag.rerank`，全部新文件）

1. `RerankPort`（接口）+ `RerankCandidate`/`RerankResult`/`RerankUsage`/`RerankFlags`（类型化 record）。
2. `NoopRerankAdapter`（恒等返回原序，默认装配）。
3. `LocalLexicalRerankAdapter`（零成本确定性：query 与候选文本词法重叠/BM25-lite 打分，独立分词不依赖 hybrid Bean；同输入同输出，分数可复算）。
4. `DashScopeRerankAdapter`（条件装配 remote.enabled+Key；TextReRank.call，model=GTE_RERANK_V2，topN，returnDocuments=false；usage.totalTokens 落记录；单条>4000 token 截断标 TRUNCATED；请求>30000 token 预检拒绝标 OVERSIZED；异常经 ModelExceptionClassifier 分类）。
5. `RerankProperties`（app.ai.rag.rerank：enabled/strategy=noop|local|remote/remote.enabled/topN/超时/预算帽）+ `RerankConfiguration`（ObjectProvider 门禁，disabled 全套不装配）。
6. `RerankExceptionClassifier`（复用 resilience 范式：永久=AUTH/PARAM/QUOTA/OVERSIZED 不重试；瞬时=TIMEOUT/TRANSIENT 应用层单次重试；失败 fail-open）。

### P2 编排挂点 + 观测（白名单追加式）

| 文件 | 改动 |
|---|---|
| RagOrchestrator | retrieve SUCCESS 后、上下文组装前**追加** rerank 步（ObjectProvider<RerankPort>，缺席=原序）；rerank 失败回退原序 + RERANK_FAILED_OPEN flag；**不改 FAILED/EMPTY 分支语义、不改权限预门禁** |
| RagTimingView / RagBudgetView | 追加 rerank 阶段耗时/usage/flags 字段（additive，既有字段不动） |
| application.yml | 仅追加 app.ai.rag.rerank 段，不改既有行 |

### P3 实验脚本 + 排序指标（temp_docs\D42，不改冻结评测器）

1. `d42_rerank-experiment.ps1`：按臂驱动评测器（A0 disabled / A1 local / A2 remote 门禁 / A3 截断可选），同数据集同参数，逐题落 orderBefore/orderAfter/scores/latency/tokens/exception → evidence\D42\rerank\runs\<arm>\*.jsonl。
2. `d42_rank-metrics.ps1`（或 java）：由有序 returnedLogicalIds + expectedChunkIds 复算 MRR@5/nDCG@5/hit@1/首个相关位次，臂间 Δ 对照 → summary.csv。
3. `d42_decision.ps1`：读 KP4 冻结阈值 + 实测 → 逐条对照裁决 adopt/not-adopt → decision.md（含理由与 NOT RUN 臂声明）。

### P4 异常矩阵 + 回归门禁

1. 异常矩阵 E1–E5（§4.3）受控注入（远程臂用受控错误响应/超时模拟，禁破坏性操作；本地臂用构造输入）。
2. 回归（D40 C05 口径）：**disabled 态全列 diff=0**；**enabled 态集合型列 diff=0 + returnedLogicalIds 变化落实验记录**；SelfTest 75/75；匿名探针零差异；夹具前后各一次。
3. 清理：PG d42_vector_store 夹具行 + MySQL 共享表前缀限定，FIXTURE_REMAINING=0，遗留零触碰，指纹恢复。

### P5 报告与文档双交付

1. `docs\proj_docs\D42-01_Rerank价值评估全量信息与操作.md`：全量事实、命令、臂定义、指标公式、决策门禁实测对照、异常矩阵、费用明细、用例落表、踩坑。
2. `docs\proj_docs\D42-02_Rerank价值评估技术文档与实践指南.md`：是什么→为什么→底层原理（bi-encoder 召回 vs cross-encoder 精排、集合不变性与排序敏感指标、fail-open、端口适配器、预算门禁）→**rerank 实验流程图（Mermaid graph TD，正序 + 失败回退分支 + 决策门禁，Typora 0.11.18 兼容 subgraph ID [标题] + 节点英文双引号）**→核心代码→常见问题→工程实践。
3. 状态文件 `docs\evidence\D42\iteration_records\D42_state_R01.md`（§7 模板）。

---

## §3 工程深度专项

### 3.1 集合不变性与排序敏感指标（D2 核心）

- 纯重排同一 Top-K 集合：recall/precision/docHit/noise/hitCount/relevantCount **数学上不变**（集合型指标只关心成员不关心顺序）→ 这些列 diff=0 既是回归守卫也是「rerank 未改成员」的证明。
- 收益度量必须排序敏感：**MRR@5 = mean(1/首个相关命中位次)**；**nDCG@5 = DCG@5/IDCG@5**（二值相关 rel∈{0,1}，DCG=Σ rel_i/log2(i+1)）；**hit@1 = 首位即相关的比例**；首个相关命中位次均值。相关真值=数据集 expectedChunkIds。
- returnedLogicalIds 有序（评测器 String.join(";", ids) 按响应序）→ 新脚本离线复算，**不改冻结评测器**。
- 截断臂 A3（rerank→top3）改变集合 → recall/precision 可能变，单独标注，不与纯重排臂混算。

### 3.2 决策门禁（KP4 预注册，禁事后调）

| 维度 | 采用阈值（示例冻结值，KP4 确认） |
|---|---|
| 相关性收益 | ΔMRR@5 ≥ +0.05 且 ΔnDCG@5 ≥ +0.05 且 hit@1 不降 |
| 延迟 | 本地 p95 附加 ≤ 300ms；远程 p95 附加 ≤ 1500ms |
| 异常 | fail-open 触发率 ≤ 1%，无未分类异常 |
| 费用 | 远程每千问费用 ≤ KP4 冻结上限（实测 token×0.8/1e6） |
| 裁决 | 全满足 → **保留为可选路径（enabled 仍默认 false，opt-in）**；任一不满足 → **不采用**（记录理由）；远程臂 NOT RUN → 决策仅基于本地臂，远程标待验 |

### 3.3 fail-open 与韧性（D5）

- rerank 是增值步，**任何失败回退原始向量序**，检索状态保持 SUCCESS/EMPTY，绝不 FAILED。
- 永久错误（NoApiKey/鉴权/参数/配额/OVERSIZED）不重试；瞬时（超时/5xx）应用层单次重试（对齐 maxAttempts=1 关内置重试约定）后回退。
- 空候选→noop；单条>4000 token 截断标 TRUNCATED；请求>30000 token 预检标 OVERSIZED 回退。
- 远程超时/连接参数经 RerankProperties 冻结；RPM 5040 上限下本轮 call 数远低于限流。

### 3.4 继承教训清单（D36–D41 踩坑，实现必须规避）

- Result 包装 HTTP 恒 200：测试以 code 判成败；拒绝=4030；空检索≠失败；分母 0 标 N/A。
- **不改冻结评测器/数据集/基线**：排序指标走新增脚本；评测器全量 javac 重编（避免内部类缺失）。
- **returnedLogicalIds 在质量投影内且有序**：enabled 态回归不得把顺序变化误判为回归失败（C05 双态语义）。
- 多构造候选显式 @Autowired/Qualifier；ObjectProvider 门禁 disabled Bean 缺席=零差异。
- 权限预门禁在 try 外（fail-closed），rerank 挂点不得把门禁吞进 catch→FAILED。
- MySQL/PG 表跨 Dxx 共享：夹具前缀限定清理，禁误触遗留 DOC-*/chunk_other 32。
- javac -encoding UTF-8；temp_docs 被 git 忽略，源码提交 git add -f 先例（token JSON 不提交，classes/target/out/surefire-reports 不提交）。
- runExit=1=冻结基线已知业务 FAIL 契约（Evaluator fail>0→1），非执行失败。
- D41 续表 id 重映射修复（b479680）已在代码，迁移测试时一并继承不回退。

---

## §4 验收标准（L1–L5 + C01–C20 + 熔断）

### 4.1 分级定义

| 级 | 内容 | 本轮要求 |
|---|---|---|
| L1 | 编译/D41 测试迁移继承/rerank 新单测/前端 6/6 | 必须 PASS |
| L2 | 探测门禁/seeding 指纹/协议冻结/零差异（disabled） | 必须 PASS |
| L3 | 实验臂执行/排序指标/决策门禁/异常矩阵/enabled 集合不变 | 必须 PASS（远程臂可 NOT RUN） |
| L4 | 权限/凭据卫生/预算/清理 | 必须 PASS |
| L5 | 跨机复现 | NOT RUN（单机），如实标注 |

四态逐用例落表，计数合计=用例总数；N/A 须写原因。

### 4.2 逐条用例（证据路径相对 `docs\evidence\D42\`）

| 编号 | 用例 | PASS 判据 | 证据 |
|---|---|---|---|
| C01 | 编译+测试继承+新单测 | mvn test 退出码 0；**D41 15 测试文件迁移后全绿** + rerank 新单测（端口契约/noop 恒等/local 确定性/fail-open/截断/分类器）全绿；前端 6/6 | compile-test.txt |
| C02 | KP1–KP4 | 四门禁裁决留痕；javap 实证；rerank-protocol.json 冻结（阈值先于结果） | probe\ |
| C03 | seeding+指纹 | d42_vector_store=8 片段；指纹=10fc69d5… 逐字节一致；遗留清单冻结 | probe\kp2-* |
| C04 | 冻结资产核验 | 数据集 SHA/基线 60 行/评测器重编 SelfTest 75/75 | probe\kp3-* |
| C05 | 零差异回归（双态） | **disabled 全列 diff=0**；**enabled 集合型列 diff=0 + returnedLogicalIds 变化落实验记录**；匿名探针零差异 | regression\ |
| C06 | A0 基线臂 | 60 题原始 Top-K 有序结果落 jsonl；排序指标基线值 | rerank\runs\A0\ |
| C07 | A1 本地臂 | 零成本词法精排；确定性（同输入同输出）；排序指标 Δ 对照 | rerank\runs\A1\ |
| C08 | A2 远程臂 | 授权+Key→受控执行，usage/费用实测；未授权/无 Key→**NOT RUN** 如实标注 | rerank\runs\A2\ |
| C09 | A3 截断臂（可选） | rerank→top3 集合变化单独标注；recall/precision 对照 | rerank\runs\A3\ |
| C10 | 排序指标复算 | MRR@5/nDCG@5/hit@1/首个相关位次由有序 ids+真值复算，口径=KP4 | rerank\summary.csv |
| C11 | 耗时对照 | rerank 阶段 p50/p95/max + 端到端，warmup 排除，分臂 | rerank\latency.csv |
| C12 | 费用对照 | 远程实测 token×0.8/1e6；本地 0；零远程标估算/NOT RUN | rerank\cost.csv |
| C13 | 决策门禁 | 逐阈值实测对照→adopt/not-adopt 裁决 + 理由；阈值未被事后修改 | rerank\decision.md |
| C14 | E1 鉴权/参数失败 | 永久错误不重试，fail-open 回退原序 + 标记 | exceptions\e1-auth.json |
| C15 | E2 超时/瞬时 | 应用层单次重试后回退，不放大 | exceptions\e2-timeout.json |
| C16 | E3 空候选 | noop，检索状态不变 | exceptions\e3-empty.json |
| C17 | E4 超长截断 | 单条>4000 截断标 TRUNCATED；请求>30000 标 OVERSIZED 回退 | exceptions\e4-oversize.json |
| C18 | E5 配额/限流 | 429/quota 分类回退，预算帽生效 | exceptions\e5-quota.json |
| C19 | 清理+回归复验 | FIXTURE_REMAINING=0；遗留零触碰；指纹恢复；disabled 回归再 diff=0 | cleanup\ + regression\ |
| C20 | 卫生+预算+文档+Git | sk-/Bearer 零命中；远程预算帽未超；D42-01/02+状态文件齐全；提交核验不 push | security\ + cost\ + git\ |

### 4.3 异常矩阵（每类含触发方式 + 处置证据）

| 编号 | 场景 | 受控触发 | 验收标准 |
|---|---|---|---|
| E1 | 鉴权/参数永久错误 | 无效 Key/非法参数（远程臂）或构造异常 | 不重试，fail-open 回退原序 + RERANK_FAILED_OPEN，检索仍 SUCCESS |
| E2 | 超时/瞬时错误 | 受控超时注入 | 应用层单次重试后回退，无重试放大 |
| E3 | 空候选 | EMPTY 检索结果 | noop，状态不变，零调用 |
| E4 | 超长输入 | 构造>4000 token 候选/>30000 请求 | 截断标 TRUNCATED / 预检标 OVERSIZED 回退 |
| E5 | 配额/限流 | 预算帽触发/429 模拟 | 分类回退，call/token/费用三重帽生效 |

### 4.4 熔断条件（命中即停，写状态文件后报告，不得绕过）

1. 同一根因连续失败 2 次 → 停止该链路，持久化现场，等待人工确认。
2. KP2 指纹漂移 / C05 disabled diff≠0 / enabled 集合型列变化 / 遗留 Chunk 被改 → 停止，先修资产。
3. 夹具清理误触遗留 / FIXTURE_REMAINING≠0 → 立即停止，前缀限定核查。
4. **需要引入新 rerank 框架/依赖才能推进 → 停止请示（H3）**；需破坏性操作 → 停止请示。
5. **决策阈值被事后修改 / 指标口径看结果后调整 → 实验完整性缺陷，立即停止，重冻协议重跑**。
6. 远程预算帽超限（call>400/token>2M/费用>¥5）→ BUDGET_EXCEEDED 立即停远程臂。
7. rerank 失败把检索打成 FAILED / bypass 权限谓词 / 未授权远程调用 → 安全或契约缺陷，立即停止。

---

## §5 成本与预算（不伪称预算）

| 项 | 规则 |
|---|---|
| 本地臂/回归 | local Embedding 零网络；生成走 mock；one_ 默认关；rerank.remote 默认关 → **DeepSeek 0、百炼 0、远程 rerank 0、Token 0** |
| 远程臂（门禁） | 须 RERANK_REMOTE_ENABLED=true + DASHSCOPE_API_KEY 显式注入；**三重帽 call≤400 / token≤2M / 费用≤¥5**（KP4 冻结）；费用=实测 totalTokens×0.8/1e6 元；超限即停 |
| 累计 Token 阈值模板 | 300 万提示 / 400 万暂停复核 / 500 万硬停止（远程 rerank token 单独计帽，模板保留） |
| 计算资源 | 60 题×（warmup3+rounds3）×臂数；本地词法 O(n)；远程受 RPM 5040 远低于限流；计时 nanoTime |
| 凭据管理 | DASHSCOPE_API_KEY/PG_VECTOR_PASSWORD/AI_API_KEY 仅环境变量（只验存在不回显）；夹具 token 明文仅 temp_docs\D42\（不提交），DB 存 SHA-256 |

---

## §6 交付物清单与 Git 规则

| # | 交付物 | 路径 |
|---|---|---|
| 1 | rerank 代码（rag.rerank 新包 + 白名单追加）+ D41 测试迁移 | com.zl.d42.rag.rerank 全套 + §2 白名单 + src/test 迁移 |
| 2 | 实验/排序指标/决策脚本 + seeding 脚本 | docs\temp_docs\D42\（保留不删） |
| 3 | 探测与协议冻结 | docs\evidence\D42\probe\ |
| 4 | 重排实验记录（逐题 jsonl + summary/latency/cost csv） | docs\evidence\D42\rerank\runs\ + *.csv |
| 5 | 采用/不采用决策记录 | docs\evidence\D42\rerank\decision.md |
| 6 | 异常矩阵证据 | docs\evidence\D42\exceptions\ |
| 7 | 回归/清理/安全证据 | docs\evidence\D42\{regression,cleanup,security}\ |
| 8 | 两份技术文档 | docs\proj_docs\D42-01_…全量信息与操作.md、D42-02_…技术文档与实践指南.md |
| 9 | 迭代状态文件 | docs\evidence\D42\iteration_records\D42_state_R01.md |
| 10 | 运行日志 | logs\D42\（surefire-reports/评测器 out 禁提交） |

Git：骨架已提交（a086fa2），迭代提交仅含新源码、白名单改动、迁移测试、脚本**源码**（temp_docs 被忽略，git add -f 先例，token JSON 不提交）、evidence\D42、proj_docs 两份、状态文件；D37/D40/D41 资产与 MySQL/PG 遗留行零改动；提交前 `git diff --cached --stat` 核验；提交信息 `D42 项目迭代R01 Rerank价值评估`；不 push。临时文件一律不自动删除。

---

## §7 状态文件模板（D42_state_R01.md）

```markdown
# D42_state_R01 —— Rerank 价值评估（one 仓）
- 迭代/切片：D42 / R01；骨架基线 commit：a086fa2（已提交，执行时复核）；本切片交付 commit：<git>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / dashscope-sdk 2.23.6 / MyBatis-Plus 3.5.17 / PG 18.7+pgvector 0.8.7 / MySQL 8
- 冻结参数：datasetHash=75cc8d83cfb2… baselineFingerprint=10fc69d5… denyCode=4030 retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3} rankMetrics={MRR@5,nDCG@5,hit@1,firstRelevantRank} decisionThresholds=<KP4> remoteBudget={maxCalls:400,maxTokens:2M,maxCny:5} rerankPrice=0.8元/M tokens（gte-rerank-v2）
- 偏差裁决：D1 端口适配器挂 orchestrator / D2 集合不变性+排序敏感新指标+C05 双态 / D3 决策门禁预注册 / D4 实测 token×官方单价 / D5 fail-open+异常分类 / D6 实验记录走文件 / D7 耗时口径 / D8 同冻结数据集 / D9 骨架已提交+D41 15 测试迁移 / D10 类型化契约（执行时确认）

## 门禁与用例落表
| 编号 | 状态(PASS/FAIL/BLOCKED/NOT RUN/N/A) | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | … | probe\… | … |
| C01–C20 | … | … | … |
| E1–E5 | … | exceptions\… | 触发+处置证据 |

## 分级结论
L1=… L2=… L3=… L4=… L5=NOT RUN（单机）

## 实验与决策结论
- 臂执行状态（A0/A1/A2/A3，远程 NOT RUN 须声明）；排序指标 Δ；决策=adopt/not-adopt + 逐阈值对照

## 成本实耗
DeepSeek calls=0 tokens=0（mock）；百炼 calls=0（one_ 关）；远程 rerank calls=… tokens=… 费用=…元（或 NOT RUN）；夹具清理 FIXTURE_REMAINING=0；指纹恢复基线

## 诚实声明
- 已执行并通过：… / 已执行但失败：… / 尚未执行：… / N/A 项及原因（远程臂未授权/截断臂可选）：…

## 遗留与 D43 候选
hybrid+rerank 组合臂 / 远程臂真实端到端（若本轮 NOT RUN）/ cross-encoder 本地模型评估 / D41 文档与状态文件缺口（观察项）/ 跨机 L5
```

---

## §8 官方文档溯源（执行时复核，不锁定记忆值）

- 《DashScope SDK — TextReRank API | 百炼排序模型 Java SDK》：https://help.aliyun.com/zh/model-studio/text-rerank-api （TextReRank.call/TextReRankParam/usage 依据，javap 实证 jar 签名）
- 《gte-rerank-v2 — 阿里云百炼 | gte-rerank-v2 模型信息与价格》：https://docs.bailian.console.aliyun.com/zh/model-studio/gte-rerank-v2 （**0.8 元/百万 tokens**、500 docs/4000 token 单条/30000 token 请求/RPM 5040 依据）
- 《LangChain 集成百炼模型代码示例 | 重排序模型价格表》：https://help.aliyun.com/zh/model-studio/use-bailian-in-langchain （gte-rerank-v2 0.0008 元/千 token 交叉印证）
- 《Spring AI Reference — ETL Pipeline / Retrieval | Spring AI 检索》：https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html （VectorStore.similaritySearch topK/threshold 依据）
- 本地权威依据：D37 冻结基线/数据集/评测器（docs\evidence\D37\、docs\temp_docs\D37\evaluator\src\d37eval\）、D40 C05 回归口径（docs\temp_docs\D40\d40_c05-regression.ps1）、D41 seeding 先例（docs\temp_docs\D41\d41_index-corpus.ps1）、D42 骨架实扫（§1.1）。

> 溯源纪律：不得伪造文档地址/源码内容/指标/费用/测试结果；无法确认的信息标「待运行时核验」。本任务书所有实测值来自编制时真实扫描，执行时若漂移，以执行时实读为准并在状态文件记录差异。决策阈值先于结果冻结，收益不明确则不采用，绝不伪造收益或事后调阈值。