# D38 项目迭代任务书 Prompt（DB ）——阿里百炼 Embedding 双轨接入（one_ 前缀隔离）

> 本文件是可直接交给执行 Agent 的完整任务书。执行 Agent 须先通读全文，再按 §1 复核基线、按 §2 分阶段实施、按 §4 逐条验收、按 §7 落状态文件。
> 核心原则：**本地玩具哈希 Embedding 链路零行为变更；阿里百炼 Embedding 走全新一套 `one_` 前缀的表、脚本、Bean、配置与端点；两轨向量空间物理隔离，永不混用。**
> 编制日期：2026-11-21。编制依据：对 `E:\AI\project\AI-Learning-one\M3-Java\D38` 的真实扫描（§1），非记忆假设。

---

## §0 迭代总览与硬契约

### 0.1 目标

在 D38 模块（`D38\D38_QWE_MROQRE`，包 `com.zl.d38`）中新增阿里百炼 Embedding 模型作为**第二条向量链路**（下称 one_ 轨），与既有本地哈希词面模型（下称 local 轨）并存：

1. local 轨：`LocalHashingEmbeddingModel`（384 维 FNV-1a 特征哈希）及其全部表、脚本、端点、行为**保持不变**，D37 冻结基线必须仍可复现。
2. one_ 轨：用户指定模型 `qwen3.7-text-embedding`（**型号名待探测核实，见 §1.3 G1**），经 OpenAI 兼容端点 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 调用；密钥取用户环境变量 `DASHSCOPE_API_KEY`（已实测存在）。
3. one_ 轨新建一整套适配资产，统一固定前缀 `one_`（表/脚本）或 `one`/`One`（包/Bean/配置/端点），与 local 轨可一眼区分。
4. 产出 one_ 轨检索评测基线（复用 D37 冻结数据集），并证明 local 轨无回归。

### 0.2 硬契约（违反任意一条即任务失败）

| 编号 | 契约 | 说明 |
|---|---|---|
| H1 | local 轨零行为变更 | 既有表结构、既有脚本、既有端点响应、既有检索/决策行为不得改变。**唯一允许的既有文件改动**：`EmbeddingConfiguration.localHashingEmbeddingModel` 与 `PgVectorStoreConfiguration.pgVectorStore` 两个 `@Bean` 方法各加一个 `@Primary` 注解（双 Bean 共存的最小装配修复，见 §3.1）。除此之外不得修改任何既有 Java/yml/sql/前端文件的逻辑。 |
| H2 | one_ 前缀隔离 | PG 表 `one_d38_vector_store`；MySQL 表 `one_document_vector_index`、`one_document_chunk_index`；Java 包 `com.zl.d38.one`；Bean 名 `oneXxx`；配置前缀 `app.one.*`；端点前缀 `/api/v1/one/**`；脚本文件名 `one_*.ps1`；评测器包 `oneeval`；基线后端目录 `ONE_PGVECTOR`。 |
| H3 | 向量空间永不混用 | 一次检索/入库只用一个 `vectorSpaceId` 对应的模型与表；one_ 轨查询绝不打 local 表，反之亦然；跨空间复用向量必须被兼容性检查拒绝（`MODEL_SPACE_MISMATCH`）。 |
| H4 | 密钥卫生 | `DASHSCOPE_API_KEY` 只经环境变量注入；禁止写入任何文件、日志、Git、PATH、URL 查询串；HTTP `Authorization` 头与错误体一律脱敏；违反即熔断。 |
| H5 | 探测先行 | 模型名、维度、批量上限、归一化、确定性**全部以运行时探测为准**（§1.3 门禁 G1–G5）；任一关键门禁失败 → 停止并如实报告，**禁止静默替换用户点名模型、禁止猜测维度建表**。 |
| H6 | 真实调用最小化 | 真实百炼调用仅用于必要探测与正式评测；全程计数计 Token（§5），超预算熔断；mock/离线场景不得触发远程调用。 |
| H7 | 不虚构结果 | 编译/测试/探测/评测必须真实执行；区分「已执行并通过」「已执行但失败」「尚未执行」；不可测项标 N/A 并写明原因，不得填 0 冒充。 |
| H8 | 破坏性操作禁令 | 禁 Docker prune/删卷/重建容器；禁 DROP 既有表；禁覆盖 D37 冻结基线与数据集；夹具用后必须清理（两轨都查）。 |

### 0.3 范围边界（本轮 R01 明确不做）

- one_ 轨 SSE 流式端点（`/api/v1/one/rag/ask/stream`）：不做，验收标 NOT RUN，留 D39。
- one_ 轨前端 UI 集成：不做；前端只验证 local 轨不回归。
- 混合检索（local+one 融合排序）、查询改写：模块描述中的后续实验，不在本轮。
- `dashscope-sdk-java` 原生 DashScope API 通道：POM 已含依赖但本轮不使用（保留为 D39 备选），本轮走 OpenAI 兼容 HTTP。
- 生产级鉴权/限流/多租户：本项目为本地学习环境，不做。

### 0.4 执行前置阻塞项（须用户先补齐，否则 P0 无法开始）

| 阻塞项 | 现状 | 需要用户提供 |
|---|---|---|
| B1：`{WorkspaceId}` | **已解决（2026-11-21 用户补齐）**：完整 baseUrl = `https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`（工作空间 ID `ws-b5h7p6oc83qc6dn5`） | 代码仍只经环境变量 `BAILIAN_EMBEDDING_BASE_URL`（默认值即此地址）注入，**禁止在 Java 代码中硬编码/提交 Git**；本任务书为本地 temp_docs 文件，可记录该地址。 |
| B2：模型名核实 | `qwen3.7-text-embedding` 不是百炼公开常见型号（公开常见为 `text-embedding-v1/v2/v3/v4`、`qwen3-embedding-0.6b/4b/8b` 系列）。可能是专属部署/新模型/笔误 | 无需用户预先确认——由 P0 探测门禁 G1 裁决：探测通过则用用户原名；探测返回模型不存在则**停止并报告原始错误码**，请用户核对控制台模型列表后再续，不得自行改名。 |

> 执行 Agent 注意：B1 已解决，P0 探测脚本默认使用上述完整地址（允许 `BAILIAN_EMBEDDING_BASE_URL` 环境变量覆盖）；若环境变量指向其他地址须显式确认；不得用标准公共端点 `dashscope.aliyuncs.com` 偷换用户点名的专属端点。

---

## §1 基线核实结果（编制时真实扫描，执行时须复核）

### 1.1 已核实事实（证据均为实读/实测）

| 项 | 实测值 |
|---|---|
| Git 基线 | HEAD `e05311f`（"D38 项目基础骨架"），工作区干净；前序 `8c77070`（D37 R02 已提交评测器源码与脚本）、`13886f4`（D38 知识笔记） |
| 模块 | `D38\D38_QWE_MROQRE`，包 `com.zl.d38`，启动类 `D38SpringAiApplication`，端口 8080，main Java 251 个，`target\classes` 已存在（骨架已编译过） |
| 技术基线（父 POM 实读） | Spring Boot 3.5.16 / JDK21 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17 / Spring Cloud 2025.0.0 / Spring Cloud Alibaba 2025.0.0.0 |
| 骨架已加依赖 | `com.alibaba:dashscope-sdk-java:2.23.6`、`com.openai:openai-java:4.107.0`（属性名拼写为 `opanai.java.verison`，系骨架既有拼写，**不改名**，仅记录）；两 jar 均已存在于本地仓库 `D:\SoftwareInstall\Apache\repository`，可离线解析 |
| Spring AI OpenAI starter | 本地仓库**无** `spring-ai-starter-model-openai`（若走该路线需联网下载；本任务书不选该路线，见 §2 P1 决策） |
| local Embedding | `app.ai.embedding`：`local / local-hashing-embedding / local-v1 / 384 / COSINE / max-input 8192`；`vectorSpaceId = provider\|model\|modelVersion\|dimensions`（`EmbeddingProperties.vectorSpaceId()`） |
| Embedding 装配 | `EmbeddingConfiguration`：单个 `EmbeddingModel` Bean，带 `@ConditionalOnMissingBean(EmbeddingModel.class)` + `@ConditionalOnProperty(app.ai.embedding.enabled)`。**陷阱：新增第二个 EmbeddingModel Bean 会触发条件回退/类型注入歧义，见 §3.1** |
| PG 装配 | `PgVectorStoreConfiguration`：`@Primary` MySQL dataSource/jdbcTemplate；独立 `pgVectorDataSource`/`pgVectorJdbcTemplate`；单 `PgVectorStore` Bean，表 `d38_vector_store`，`vector(384)`，HNSW + COSINE_DISTANCE，`initialize-schema=true`；建 Bean 时断言 `embeddingModel.dimensions()==config.dimensions`，不一致即抛异常禁止建表 |
| 按类型注入点（无 Qualifier） | `SimilaritySearchService`、`DocumentEmbeddingService` 构造器注入 `EmbeddingModel`；`pgVectorStore(...)` 注入 `EmbeddingModel`。新增 Bean 后这些注入点必须仍解析到 local（靠 `@Primary`） |
| MySQL 向量链表 | `document_vector_index`（`uk_doc_version(document_id,version)`，状态机 PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED）；`document_chunk_index`（`uk_chunk_id` 全局唯一 + `uk_doc_ver_strat_idx(document_id,version,strategy,chunk_index)`，upsert **不更新 chunk_id 列**） |
| 共享文档表 | `knowledge_document`（lifecycle_status）、`document_page_content`：文档身份/生命周期，与模型无关，**两轨共享、不改结构** |
| chunkId | `StableChunkIdGenerator`：UUID v5，name=`documentId\|version\|strategy\|chunkIndex\|chunkContentHash\|splitVersion`，固定 NAMESPACE，**不含模型身份** → 同一逻辑 chunk 在两轨产生相同 UUID（设计利用点见 §3.4） |
| 入库幂等 | `DocumentVectorIndexService.indexChunks`：COMPLETED+同 payloadHash+关联数一致 → 短路；写后核对 pgCount==relationCount==chunkCount，否则 COUNT_MISMATCH/PARTIAL；版本切换旧 COMPLETED→SUPERSEDED+cleanup |
| 检索契约 | `POST /api/v1/retrieval/search` → `data.matches`（不是 hits）+ total + vectorSpaceId + filtered + 三段计时；threshold 为 similarity 语义（similarity=1−distance，COSINE）；topK 1..20 默认 5；显式 Query Embedding + 零向量拒绝 |
| 决策链 | `EvidenceAssessmentService`：生命周期筛选（仅 ACTIVE，fail-closed）→ CONFLICT → NO_EVIDENCE → LOW_RELEVANCE（全部 sim<0.55）→ CLARIFY（跨≥2 title）→ INSUFFICIENT_EVIDENCE（术语覆盖<0.60）→ ANSWER；`FactClaimExtractor` 只匹配已知参数标签+数值 |
| 生成 | `AI_MODE` 默认 mock → 无 ChatModel Bean → `NOT_CONFIGURED/MODEL_NOT_CONFIGURED`（受控态非错误）；deepseek+密钥缺失启动 fail-fast |
| 语料 | `src\main\resources\knowledge\equipment-fault-corpus.json`：**顶层数组**，3 文档 8 片段（DOC-CNC-COOLING-001×3、DOC-SERVO-TROUBLE-002×3 含注入教学 C03、DOC-WORKORDER-SPEC-003×2）；3 文档 lifecycle_status=ACTIVE（D37 已登记） |
| D37 资产 | 冻结数据集 `docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl`（20 问，哈希前缀 75cc8d83cfb2）+ 冻结基线 `docs\evidence\D37\baseline\v1.0\{IN_MEMORY_LOCAL,PGVECTOR}\`（永久只读）；评测器源码 `docs\temp_docs\D37\evaluator\src\d37eval\`（9 文件，R02 已提交 Git）；QA-001 local 受控探针 Top1 similarity=0.3797（D37 冻结值） |
| 环境 | PG 192.168.200.153:5432/spring_ai（postgres，口令环境变量 `PG_VECTOR_PASSWORD`，Windows 用户级已设）；MySQL 127.0.0.1:3306/ai_db（root，`MYSQL_PASSWORD` 默认 123456）；Redis 6379 不可达但不阻断启动；`DASHSCOPE_API_KEY` 用户环境变量**已存在，长度 116**（非典型 sk- 35 字符格式，须探测验证有效性，不回显） |
| 工具链 | Maven `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`（JDK21，本地仓库 `D:\SoftwareInstall\Apache\repository` 两级 settings 已生效，**勿传 -Dmaven.repo.local**）；无 mysql CLI（用 jshell + `D:\SoftwareInstall\Apache\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar`）；Node v22.23.2；PowerShell 中文输出须 `[Console]::OutputEncoding=UTF8` + `JAVA_TOOL_OPTIONS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"` |
| 目录规范 | 正式文档 `docs\proj_docs`；证据 `docs\evidence\D38`；状态 `docs\evidence\D38\iteration_records\D38_state_R01.md`；任务书/脚本/评测器 `docs\temp_docs\D38`（.gitignore 忽略 /docs/temp_docs/，源码提交沿用 D37 R02 先例 `git add -f`）；日志 `logs\D38`（*.log 忽略）；surefire-reports 禁提交 |

### 1.2 执行时复核命令（可执行，PowerShell 7）

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-one\M3-Java"
git -C $r rev-parse HEAD; git -C $r status --porcelain
# 版本与依赖
Select-String -Path "$r\D38\pom.xml" -Pattern 'spring-ai.version|dashscope.sdk.version|opanai.java.verison|java.version'
Test-Path "D:\SoftwareInstall\Apache\repository\com\alibaba\dashscope-sdk-java\2.23.6\dashscope-sdk-java-2.23.6.jar"
Test-Path "D:\SoftwareInstall\Apache\repository\com\openai\openai-java\4.107.0\openai-java-4.107.0.jar"
# 密钥存在性（只输出布尔与长度，禁止回显值）
$k=[Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User'); "present=$(-not [string]::IsNullOrEmpty($k)) length=$($k.Length)"
"WORKSPACE_ID present=$(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable('DASHSCOPE_WORKSPACE_ID','User')))"
"BASE_URL present=$(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable('BAILIAN_EMBEDDING_BASE_URL','User')))"
# 编译复核
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -f "$r\D38\pom.xml" -pl D38_QWE_MROQRE -q compile
```

### 1.3 未核实项与探测门禁（P0 必须逐项裁决，证据落 `docs\evidence\D38\probe\`）

| 门禁 | 待核实项 | 探测方法 | 通过判据 | 失败处置 |
|---|---|---|---|---|
| G1 | 端点可达 + 模型名有效 + 密钥有效 | `one_probe-embedding.ps1`：POST `{base}/embeddings`，body `{"model":"qwen3.7-text-embedding","input":["你好"],"encoding_format":"float"}`，Header `Authorization: Bearer <env>`（脚本内拼接，不落盘不回显） | HTTP 200 且 `data[0].embedding` 非空 | 401/403 → `AUTH_FAILED`（核对密钥格式，116 字符非典型）；404/模型不存在 → `MODEL_NOT_FOUND`，**停止报告原始错误码，请用户核对控制台，禁止改名重试**；网络不通 → `ENDPOINT_UNREACHABLE`（检查代理/防火墙，cn-beijing maas 专属端点） |
| G2 | 输出维度 N | 取 G1 响应 `len(data[0].embedding)` | N 为正整数且 ≤2000（HNSW vector 上限，见 §3.3）；若模型支持 `dimensions` 参数且 N>2000，探测降维到 ≤2000 的合法值 | N>2000 且无合法降维 → `DIMENSION_EXCEEDS_HNSW`，停止报告备选（halfvec/换模型），不得强行建表 |
| G3 | 批量上限 B 与单条 Token 上限 | 依次 input 长度 2/8/10/16/25 探测（复用同一短文本，计 5 次调用内）；记录首个 4xx 的批量数 | 得到最大合法 B；语料 8 chunk 须能按 B 分批 | 全部失败 → 回退 B=1 并记录 |
| G4 | 归一化与距离方向 | 计算 G1 向量 L2 范数 | ‖v‖₂≈1（±1e-3）→ 输出已归一化，COSINE 口径与 local 轨一致；否则记录 `NOT_NORMALIZED`，仍用 COSINE_DISTANCE（余弦对模长不敏感）但文档明示 | 不阻断，记录事实 |
| G5 | 确定性 canary | 同一文本连续 embed 2 次，算余弦 | cosine ≥ 1−1e-6 | 不达标 → `REMOTE_NON_DETERMINISM`：one_ 基线冻结门禁（C12）将大概率失败，先报告再决定是否继续（不得伪造字节一致） |

> G1–G5 合计真实调用 ≤ 9 次（G1×1 + G3×5 + G5×2 + 余量 1），计入 §5 预算。探测响应只保留脱敏摘要（status/model/dims/usage/norm/errorCode），**完整响应体若含密钥回显一律剔除后再落盘**。

---

## §2 分阶段实施要求

### P0 探测与冻结参数（门禁先行）

1. 编写 `docs\temp_docs\D38\one_probe-embedding.ps1`：读 `DASHSCOPE_API_KEY`；baseUrl 取 `$env:BAILIAN_EMBEDDING_BASE_URL`，缺省回退默认 `https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`（B1 已解决，不再报 WORKSPACE_ID_MISSING；仅当密钥缺失时报 `API_KEY_MISSING` 退出）。
2. 执行 G1–G5，证据写 `docs\evidence\D38\probe\probe-result.json`（脱敏）+ `probe-log.txt`。
3. 冻结 one_ 轨参数到 `docs\evidence\D38\probe\one-embedding-params.json`：`{model, modelVersion, dimensions, maxBatch, maxInputTokens(若响应/文档可得), normalized, canaryCosine, baseUrlHost(不含密钥), probedAt}`。**后续 DDL/配置/评测一律引用该冻结值，禁止运行时再猜。**
4. 任一门禁失败按 §1.3 处置；G1/G2 失败 = 硬停止。

### P1 远程 Embedding 客户端与双轨装配

**客户端选型决策（已定，执行者不得静默更换）**：用 JDK `java.net.http.HttpClient` + Jackson（项目已有）手写 `OneBailianEmbeddingClient`，**不引入新依赖**。理由：超时/重试分类/usage 提取/脱敏完全可控可审计；`openai-java` 4.107.0 API 面未经本项目验证、`spring-ai-starter-model-openai` 本地仓库缺失且其自动装配会引入额外 ChatModel 条件分支风险。骨架已加的 `dashscope-sdk-java`/`openai-java` 依赖**保留不动**（本轮不使用，D39 原生通道备选）。

新增文件（全部在 `com.zl.d38.one` 下，禁改既有逻辑）：

1. `one/config/OneEmbeddingProperties.java`：`@ConfigurationProperties("app.one.embedding")`，字段 `enabled/provider=bailian/model/modelVersion/dimensions/baseUrl/apiKey/maxBatch/maxInputLength/distanceMetric=COSINE/connectTimeout/readTimeout/totalDeadline/maxAttempts`；`vectorSpaceId()` 复用 `EmbeddingModelMetadata.buildVectorSpaceId(provider,model,modelVersion,dimensions)`；启动校验：enabled=true 时 baseUrl/apiKey/dimensions 缺一即 fail-fast（**dimensions 必须等于 P0 冻结值，配置默认值留空占位，由环境变量 `ONE_EMBEDDING_DIMENSIONS` 注入冻结值**）。
2. `one/client/OneBailianEmbeddingClient.java`：POST `{baseUrl}/embeddings`；请求体 `model/input/encoding_format=float`（`dimensions` 参数仅当 P0 证实模型支持且需要降维时携带）；超时 connect 3s / read 30s / 单逻辑调用总墙钟 60s；重试仅 429/5xx/连接类 IO，最多 2 次尝试，退避 200ms×2.0 抖动 0.2（对齐既有 `app.ai.resilience` 口径）；400/401/403/404 不重试；每次调用累计 `usage.total_tokens` 与调用计数（线程安全 LongAdder），暴露给评测器读取；错误摘要 ≤500 字符、剔除 Authorization 与密钥片段（复用/扩展 `LogMaskingUtils` 模式，新增 `sk-[A-Za-z0-9]{16,}` 与 `Bearer\s+\S+` 脱敏规则时**只在新类中扩展，不改既有工具类行为**）。
3. `one/embedding/OneRemoteEmbeddingModel.java`：实现 Spring AI `EmbeddingModel`；`embed(String)`/`embed(List<String>)` 按 `maxBatch` 分批；**覆写 `dimensions()` 直接返回冻结配置值，禁止触发网络**（接口默认实现会真实 embed 一次，启动期零调用红线）；零向量/空响应/维度不符 → 抛受控异常（对齐 local 轨零向量拒绝语义）；`embed(Document)` 取 text。
4. `one/config/OneEmbeddingConfiguration.java`：`@ConditionalOnProperty(prefix="app.one.embedding",name="enabled",havingValue="true")`（**默认 false，matchIfMissing 不设**）；Bean `oneEmbeddingModel`（@Qualifier 同名）。
5. `one/config/OnePgVectorStoreConfiguration.java`：复用既有 `pgVectorJdbcTemplate`（@Qualifier 注入，同一 PG 库不同表）；Bean `onePgVectorStore`：表名取 `app.one.pgvector.table`（默认 `one_d38_vector_store`），dimensions 取 one_ 冻结值，HNSW + COSINE_DISTANCE，initialize-schema=true；建 Bean 断言 `oneEmbeddingModel.dimensions()==oneConfig.dimensions`（对齐既有断言风格）。
6. **既有文件最小改动（H1 唯一豁免，共 2 处注解）**：`EmbeddingConfiguration.localHashingEmbeddingModel` 加 `@Primary`；`PgVectorStoreConfiguration.pgVectorStore` 加 `@Primary`。改后必须跑 C06/C07 证明 local 行为不变。
7. `application.yml` **追加**（不改既有行）：`app.one.embedding.*`（enabled 默认 false、model 默认 `qwen3.7-text-embedding`、dimensions 默认空、base-url 默认空、api-key `${DASHSCOPE_API_KEY:}`）+ `app.one.pgvector.table: ${ONE_PGVECTOR_TABLE:one_d38_vector_store}`。enabled=false 时应用启动与 D38 骨架完全一致、零远程调用。

### P2 one_ 新表 DDL（探测冻结后才允许执行）

1. 新增 `src\main\resources\db\schema-one-document-vector-index.sql`（`CREATE TABLE IF NOT EXISTS`，可重复执行）：
   - `one_document_vector_index`：复制 `document_vector_index` 全部列与索引，**新增 `vector_space_id VARCHAR(160) NOT NULL`**（绑定 one_ 空间），表注释标明 one_ 轨。
   - `one_document_chunk_index`：复制 `document_chunk_index` 全部列与索引（`uk_chunk_id`、`uk_doc_ver_strat_idx` 保持），**新增 `vector_space_id VARCHAR(160) NOT NULL`**。
   - PG 表 `one_d38_vector_store` 由 `onePgVectorStore` 的 initialize-schema 自动创建（`vector(N)`，N=P0 冻结值）；建表后用 catalog 独立核验 `pg_attribute.format_type='vector(N)'` + HNSW 索引存在（对齐 D37 证据口径）。
2. **共享表不动**：`knowledge_document`/`document_page_content` 两轨共享（文档身份与模型无关）；语料 3 文档已 ACTIVE，one_ 轨直接复用，不重复登记。
3. DDL 执行证据（jshell+JDBC 或脚本输出）落 `docs\evidence\D38\ddl\`；同时输出既有表结构快照证明未被改动（C05）。

### P3 one_ 入库链路

新增 `com.zl.d38.one.index`：`OneDocumentVectorIndexService`、`OneVectorIndexController`（`/api/v1/one/vector-index/**`）、one_ PO/Mapper（`@TableName("one_document_vector_index")` 等）、`OneVectorIndexStateTx`。

- 语义**对齐** local 轨（幂等短路、payloadHash、写后三方计数核对、版本切换 SUPERSEDED+cleanup、COUNT_MISMATCH/PARTIAL 原因码），但代码独立新写，注入 `oneEmbeddingModel`/`onePgVectorStore`/one_ Mapper（全部 @Qualifier）。
- chunkId：**复用** `StableChunkIdGenerator`（同一逻辑 chunk 两轨同 UUID，便于跨轨对比；`uk_chunk_id` 按表独立，无冲突，见 §3.4）。
- 入库请求 DTO 复用既有 `VectorIndexRequest` 结构（documentId/version/strategy/title/chunks），**新端点新 DTO 类**（one_ 包内），不改既有 DTO。
- 已知坑继承（D37 教训，必须写进 one_ 服务注释与脚本）：同文档换 strategy 重索引前必须先 DELETE 本文档 one_ 关联再重建（自然键含 strategy 且 upsert 不更新 chunk_id）；直接入库 API 无 page/section 承载字段 → PG 侧 page=null（如实记录，不伪造元数据）。
- 端点：`POST /api/v1/one/vector-index/index`、`DELETE /api/v1/one/vector-index/documents/{id}`、`GET .../chunks|status`、`POST .../reconcile {dryRun}`。

### P4 one_ 检索与 ask

1. `one/retrieval/OneSimilaritySearchService` + `OneSimilaritySearchController`（`POST /api/v1/one/retrieval/search`）：响应结构对齐 local（`data.matches`+total+vectorSpaceId+filtered+三段计时），vectorSpaceId 取 one_ 配置；threshold=similarity 语义、topK 1..20、零向量拒绝、过滤 SQL 下推——全部对齐，独立实现。
2. `one/rag/OneRagOrchestrator` + `OneRagController`（`POST /api/v1/one/rag/ask`）：**复用共享 Bean**（`EvidenceAssessmentService`、生成服务、引用核验、`UrlSafetyPolicy`）——这些组件与模型无关，只读复用、零修改；检索源替换为 `OneSimilaritySearchService`。响应契约对齐 `RagAnswerResponse`（answer/sources/retrievalStatus/generationStatus/errorCode/decision/...；sourceId=documentId 稳定、citationId=S1/S2 请求级、quotedSnippet≤200、url 恒 null）。
3. mock 生成态：one_ ask 在 AI_MODE=mock 下同样 `NOT_CONFIGURED/MODEL_NOT_CONFIGURED`（受控态）；删除竞态 CITATION_TARGET_DELETED 在 mock 下不可观测——如实标 N/A，不伪造。
4. 兼容性端点：`POST /api/v1/one/embeddings/index/compatibility`（对齐 local 语义，跨空间 → MODEL_SPACE_MISMATCH）。

### P5 one_ 脚本与评测器

全部新文件于 `docs\temp_docs\D38\`，前缀 `one_`：

1. `one_index-corpus.ps1`：读冻结语料（顶层数组！）→ 调 one_ 入库端点 → 核对 8/8 COMPLETED + 三方计数；幂等重跑短路验证。
2. `one_evaluator\`：复制 D37 `d37eval` 9 源文件为包 `oneeval`，改造点（仅限这些）：
   - 端点前缀参数化 `/api/v1/one/...`；
   - **删除 IN_MEMORY 后端**：神经模型无法本地复刻，one_ 轨后端只有 `ONE_PGVECTOR / BLOCKED / UNREACHABLE`；离线天花板文档明示（§3.5）；
   - 绑定信息改 one_ 冻结参数；质量投影仍排除浮点（similarity/计时不入字节一致投影）；
   - 新增 canary 门禁调用与 usage 汇总读取（从应用暴露的计数端点或 run  manifest 记录）；
   - `SelfTest` 同步改造并全绿后才允许正式跑。
3. `one_run-evaluation.ps1`：一条命令编译+SelfTest+正式评测（`--rounds 3`，两次独立运行 + diff）；`one_special-fixtures.ps1`：one_ 空间夹具（`D38-ONE-CONFLICT-901`/`D38-ONE-LIFECYCLE-902`，先 register ACTIVE 再索引，触发 CONFLICT/生命周期路径）；`one_fixture-cleanup.jsh`：两轨夹具残留核验（FIXTURE_REMAINING=0）。
4. 数据集：**只读复用** D37 冻结 `rag-evaluation-questions.v1.0.jsonl`（20 问），不重新标注；新 manifest 引用其 SHA-256 与哈希前缀 75cc8d83cfb2，落 `docs\evidence\D38\dataset\dataset-manifest.json`。
5. 回归脚本 `one_local-regression.ps1`：用 D37 评测器（只读）指向 D38 应用 local 端点跑 1 次完整评测，质量字段与 D37 冻结基线 diff 必须逐字节一致；另跑 QA-001 受控探针（topK=5/threshold=0.0/documentId 过滤）Top1 similarity 与 D37 冻结值 0.3797… 差 ≤1e-9。

### P6 基线冻结与回归证明

1. one_ 正式评测：≥3 轮 × 2 次独立运行，质量投影字节一致 → 冻结 `docs\evidence\D38\baseline\v1.0\ONE_PGVECTOR\`（baseline.csv/summary.md/raw-results.jsonl/evaluation-metrics.json/env-probe.json/chunk-id-mapping.json/freeze-info.json + one-embedding-params.json + usage-summary.json）。
2. 若 G5 canary 不达标或两次运行质量投影分歧 → 如实标 `REMOTE_NON_DETERMINISM`/`REPEAT_RESULT_DIVERGED`，**不冻结假基线**，报告分歧明细（哪些问句排序变化、similarity 漂移幅度），由用户裁决。
3. 冻结后目录设只读属性；后续运行只写 `runs\`，不覆盖基线。
4. local 回归证据落 `docs\evidence\D38\regression\`（diff 报告 + 探针 JSON）。
5. 可测性边界文档 `docs\evidence\D38\measurability-boundary.md`：one_ 轨无内存fallback、mock 生成态不可测项、SSE 未做、跨机 L5 未做——逐项 N/A 原因。

### P7 文档双交付（与代码同步）

1. `docs\proj_docs\D38-01-阿里百炼Embedding双轨接入-项目迭代全量信息与操作.md`：全量事实、命令、证据索引、C01–C20 落表、预算实耗、踩坑。
2. `docs\proj_docs\D38-02-阿里百炼Embedding双轨接入-技术文档说明与实践指南.md`：按「是什么→为什么→底层原理→执行流程→核心代码→常见问题→工程实践」展开；Mermaid 用 Typora 兼容 `graph TD` + `subgraph ID [标题]` + 节点英文双引号；覆盖双 Bean 装配原理、向量空间隔离、UUID v5 跨轨同 ID 设计、HNSW 维度红线、确定性门禁、成本核算。
3. 状态文件 `docs\evidence\D38\iteration_records\D38_state_R01.md`（§7 模板）。

---

## §3 工程深度与安全专项（含「超出用户预期但必须注意」清单）

### 3.1 双 Bean 装配陷阱（最高优先级，已实读证实）

- `EmbeddingConfiguration.localHashingEmbeddingModel` 带 `@ConditionalOnMissingBean(EmbeddingModel.class)`：若 one_ Bean 先注册，local Bean 直接消失；若 local 先注册、one_ 后注册，则 `SimilaritySearchService`/`DocumentEmbeddingService`/`pgVectorStore` 的按类型注入抛 `NoUniqueBeanDefinitionException`。
- 解法（H1 唯一豁免）：local 两个 Bean 加 `@Primary`；one_ Bean 全部 `@Qualifier("oneEmbeddingModel")`/`@Qualifier("onePgVectorStore")` 显式注入；one_ 配置类独立 `@ConditionalOnProperty(app.one.embedding.enabled)`，默认关闭。
- 验证：C06 列出容器内 EmbeddingModel/VectorStore Bean 清单与 Primary 标记；C07 证明 local 端点行为逐字节不变。**禁止**用「切换 `app.ai.embedding.provider=bailian`」实现接入——那会替换 local 模型，违反双轨契约（`EmbeddingProperties` 虽已预留云端校验分支，但它是单轨切换设计，不是双轨）。

### 3.2 型号/端点/密钥三件不确定事（用户原文未核实项）

- `qwen3.7-text-embedding` 非公开常见型号 → G1 裁决，失败即停，禁止静默改名（H5）。
- `{WorkspaceId}` 占位符 → B1 阻塞项，环境变量注入，禁止硬编码/提交。
- `DASHSCOPE_API_KEY` 长度 116 非典型（公开 sk-  key 约 35 字符）→ 可能是工作空间级密钥或其他格式，G1 验证有效性；**任何输出不得回显密钥本体**。
- 专属 maas 端点与公共 `dashscope.aliyuncs.com/compatible-mode/v1` 是不同入口，网络可达性独立验证；禁止偷换公共端点「先跑通再说」。

### 3.3 维度与 pgvector 索引红线（建表前必须裁决）

- local 轨 384 维；one_ 维度 N 未知（qwen3-embedding 系列公开维度 1024/2560/4096 不等，text-embedding-v4 默认 1024 且支持 dimensions 降维——**以 G2 实测为准，不引用记忆值建表**）。
- pgvector 0.8.x：**HNSW 对 `vector` 类型上限 2000 维**（`halfvec` 可到 4096）。若 N>2000：优先探测模型 `dimensions` 参数降维到 ≤2000 合法值；不支持则停止报告备选（halfvec 需自定义 PgVectorStore 写入/查询 SQL，改动面大，本轮不做；IVFFlat 同样 2000 上限）。**禁止建 vector(4096)+HNSW 的非法组合，禁止无索引全表扫冒充正式表。**
- 建表后 catalog 核验 `format_type='vector(N)'`、HNSW 索引名存在、距离算子类 `vector_cosine_ops`；N 与冻结参数、vectorSpaceId、配置四者一致（对齐 local 轨四一致口径）。

### 3.4 chunkId 跨轨同 UUID：是特性不是 Bug

- `StableChunkIdGenerator` 不含模型身份 → 同一逻辑 chunk 在 local/one_ 两轨 UUID 相同。两轨 PG/MySQL 表物理独立，`uk_chunk_id` 按表唯一，无冲突；同 UUID 反而支持跨轨按 chunk 对齐对比（D38 后续混合检索实验的基础）。
- 红线：**同 UUID 不代表向量可互换**。任何把 one_ 向量写入 local 表（或反向）的路径必须被 vectorSpaceId 兼容性检查拒绝；reconcile 按表独立核对，不得跨表 JOIN 计数。

### 3.5 神经模型没有内存 fallback（评测架构差异）

- D37 local 轨可本地复刻哈希算法 → IN_MEMORY_LOCAL 后端成立。one_ 轨神经模型无法离线复刻 → **oneeval 删除内存后端**，后端态只有 ONE_PGVECTOR/BLOCKED/UNREACHABLE。
- 「零远程调用」保证只适用于 local 轨运行与 enabled=false 场景；one_ 正式评测必然产生真实调用，靠 §5 预算约束而非零调用。
- 离线天花板、mock 生成态不可测项（删除竞态全路径、真实引用核验）逐项 N/A 写入 measurability-boundary.md，不得填 0。

### 3.6 远程非确定性与基线字节一致

- 服务端模型可能随时间漂移/批处理浮点误差 → G5 canary 门禁 + 两次运行质量投影 diff；分歧即如实报告（§2 P6），不伪造一致。
- 质量投影排除浮点（similarity/计时）是 D37 已验证设计，one_ 沿用；但**排序**对浮点敏感，近似平分可能翻转 → canary 不达标时先报告再决定是否冻结。

### 3.7 成本与限流（真实调用最小化）

- 批量上限 B、单条 Token 上限、QPS 以 G3/官方文档实时核验为准；客户端按 B 分批，429 退避重试（最多 2 次尝试），400/401/403 不重试。
- 每次调用记录 `usage.total_tokens`（API 真实返回，**禁止用字符数估算冒充 Token**）；run manifest 汇总调用数/Token 数；超 §5 预算熔断 `BUDGET_EXCEEDED`。
- 正式轮次**不做查询向量缓存**（缓存会污染 queryEmbeddingMs 测量语义）；warmup 与正式轮同口径。

### 3.8 密钥与日志卫生

- 新客户端日志只输出 model/dims/usage/耗时/错误码；`Authorization` 头、请求体密钥片段、响应中的密钥回显一律脱敏；错误摘要 ≤500 字符。
- 交付前扫描（C15）：`git grep -I -n "DASHSCOPE_API_KEY\|sk-"` 命中只允许环境变量引用行；logs/ 与 evidence/ 全文扫描密钥模式（`sk-[A-Za-z0-9]{16,}`、116 字符原值片段）零命中；surefire-reports 禁提交（含 java.library.path 等环境信息）。

### 3.9 其余继承教训（D37 踩坑清单，one_ 实现必须规避）

- javac double→Long 用 `Math.round`；Jackson 2.19 用 `properties()` 替代弃用 `fields()`；`node --test` 传文件不传目录。
- 生命周期过滤先于冲突：夹具必须先 register ACTIVE 再索引，否则 CONFLICT 永不触发。
- 换 strategy 重索引先 DELETE 再重建（upsert 不更新 chunk_id + 自然键含 strategy）。
- 直接入库 API 丢 page/section → PG page=null，如实记录。
- PgVectorStore「先 validate 后 initialize」顺序坑：沿用 Builder 默认 false + catalog 独立核验。
- 离线无决策执行时决策类指标 N/A（decisionsJudged=0 门槛），不填 0。

---

## §4 验收标准（L1–L5 分级 + C01–C20 逐条用例 + 熔断）

### 4.1 分级定义

| 级 | 含义 | 本轮要求 |
|---|---|---|
| L1 | 编译/单测/前端安全测试 | 必须 PASS |
| L2 | 集成（探测门禁/DDL/入库/检索/ask/隔离） | 必须 PASS（G1/G2 失败则整体 BLOCKED 并报告，不算 PASS） |
| L3 | 评测与基线（canary/3×2/冻结/local 回归） | 必须 PASS 或如实 BLOCKED（非确定性） |
| L4 | 安全/成本/密钥/重试/清理 | 必须 PASS |
| L5 | 跨机复现 | 默认 NOT RUN（单机环境），如实标注，不冒充 |

### 4.2 逐条用例（证据路径相对 `docs\evidence\D38\`）

| 编号 | 用例 | PASS 判据 | 证据 |
|---|---|---|---|
| C01 | 编译+单测 | `mvn -f D38\pom.xml -pl D38_QWE_MROQRE test` 退出码 0；前端 `node --test src\test\frontend\front-end-safety.test.js` 6/6 | `compile-test.txt` |
| C02 | P0 探测门禁 | G1–G5 全部裁决，冻结参数文件生成且脱敏 | `probe\probe-result.json`、`probe\one-embedding-params.json` |
| C03 | 模型名负向门禁 | 若 G1 返回模型不存在：停止+原始错误码报告，无改名重试痕迹 | `probe\probe-log.txt`（条件用例，G1 通过则标 N/A-模型有效） |
| C04 | 开关与 fail-fast | enabled=false 启动成功且零远程调用；enabled=true 缺密钥/缺 dimensions 启动 fail-fast 错误明确 | `startup\*.txt` |
| C05 | DDL 与既有表保护 | one_ 两 MySQL 表+PG 表建成，vector(N)=冻结值，HNSW+COSINE catalog 核验；既有表结构快照前后一致 | `ddl\*.json/txt` |
| C06 | 双轨装配 | 容器含 local(@Primary)+one 两个 EmbeddingModel/VectorStore；one_ 注入全 Qualifier；既有文件改动仅 2 个 @Primary（git diff 核验） | `wiring\beans.txt`、`wiring\diff-annotations.patch` |
| C07 | local 无回归 | QA-001 探针 Top1 similarity 与 D37 冻结值差 ≤1e-9；D37 评测器对 D38 local 完整 1 跑质量字段与 D37 基线 diff 字节一致 | `regression\*.json` |
| C08 | one_ 入库 | 语料 8/8 COMPLETED，pgCount==relationCount==chunkCount；幂等重跑短路；换 strategy 先 DELETE 重建路径验证 | `index\*.json` |
| C09 | one_ 检索契约 | matches/total/vectorSpaceId/filtered/三段计时齐全；topK 越界钳制；threshold similarity 语义；零向量拒绝 | `retrieval\*.json` |
| C10 | 跨空间隔离 | local 检索不含 one_ 向量（表分离+spaceId 核验）；兼容性端点对跨空间返回 MODEL_SPACE_MISMATCH | `isolation\*.json` |
| C11 | one_ ask 决策链 | 逐字问题+过滤 → ANSWER+generationStatus=NOT_CONFIGURED（mock 受控态）；夹具触发 CONFLICT/生命周期 REFUSE；引用契约 sourceId/citationId/snippet≤200/url=null 全符合 | `ask\*.json`、`special-cases\*.json` |
| C12 | one_ 评测重复性 | canary 达标前提下 ≥3 轮×2 跑质量投影字节一致；不达标 → 如实 BLOCKED+分歧明细 | `runs\*\`、`runs\consistency-diff-*.json` |
| C13 | 基线冻结 | ONE_PGVECTOR 基线 7+2 文件齐全、SHA-256 manifest、只读属性；数据集只读复用 D37 哈希一致 | `baseline\v1.0\ONE_PGVECTOR\freeze-info.json`、`dataset\dataset-manifest.json` |
| C14 | 成本核算 | 全程调用数/Token 数来自 API usage 真实累计；未超预算；manifest 含 per-phase 明细 | `usage\usage-summary.json` |
| C15 | 密钥卫生 | git/日志/证据密钥模式扫描零命中；Authorization 脱敏；surefire-reports 未提交 | `security\secret-scan.txt` |
| C16 | 重试分类 | 429/5xx 退避重试≤2 次尝试；400/401/403 不重试（用受控错误注入或真实 4xx 一次验证，计预算） | `resilience\retry-classification.json` |
| C17 | 前端与静态扫描 | local 前端 6/6；one_ 未引入前端改动；URL 协议黑名单/安全渲染静态扫描通过 | `special-cases\C17-*.txt` |
| C18 | 夹具清理 | 两轨夹具向量+MySQL 登记行全清理，FIXTURE_REMAINING=0 | `special-cases\fixture-cleanup.txt` |
| C19 | 文档双交付 | D38-01/02 + 状态文件齐全，Mermaid Typora 兼容，命令/数字与证据一致 | `docs\proj_docs\D38-01/02`、`iteration_records\D38_state_R01.md` |
| C20 | Git 规则 | 提交仅含新源码/one_ 脚本源码(git add -f)/evidence/proj_docs；无 surefire-reports/日志/classes/密钥；不 push | `git\commit-stat.txt` |

### 4.3 熔断条件（命中即停，写状态文件后报告，不得绕过）

1. G1/G2 失败（模型无效/维度超 HNSW 无解）→ 硬停止。
2. 密钥泄漏扫描命中 → 立即停止+清理+报告。
3. local 回归漂移（C07 超差）→ 停止 one_ 工作，先修复装配。
4. 预算超限（§5）→ BUDGET_EXCEEDED 停止。
5. 非确定性导致基线无法字节一致 → 不冻结假基线，如实 BLOCKED。
6. 任何需要 DROP 既有表/覆盖 D37 基线/改既有逻辑（超出 2 注解豁免）才能推进的情形 → 停止请示。

---

## §5 成本与预算（真实调用最小化，Token 口径不伪造）

| 阶段 | 调用上限 | Token 口径 |
|---|---|---|
| P0 探测 G1–G5 | ≤9 次 | usage.total_tokens 真实累计 |
| 语料入库（8 chunk，按 B 分批） | ≤4 次 | 同上 |
| one_ 正式评测（20 问×3 轮×2 跑+warmup 3×2） | ≤300 次 | 同上 |
| 夹具/探针/重试分类验证 | ≤30 次 | 同上 |
| **全程合计** | **≤350 次，≤200,000 tokens** | 超任一上限 → BUDGET_EXCEEDED 熔断 |

- 预算值可由用户环境变量 `ONE_CALL_BUDGET`/`ONE_TOKEN_BUDGET` 覆盖（默认如上）。
- 每次运行 manifest 记录 per-phase 调用数与 Token 数；**字符数≠Token，禁止估算冒充**；API 未返回 usage 时标 `USAGE_UNAVAILABLE` 并按调用数保守计，不填 0。
- 本地轨回归（D37 评测器对 D38 local）零远程 Embedding 调用（local 模型零网络），不占百炼预算。

---

## §6 交付物清单与 Git 规则

### 6.1 交付物

1. 代码：`com.zl.d38.one` 全套新类 + 2 处 @Primary 注解 + application.yml 追加段 + `db\schema-one-document-vector-index.sql`。
2. 脚本/评测器：`docs\temp_docs\D38\one_*.ps1`、`one_evaluator\`（源码）、`one_fixture-cleanup.jsh`、`one-embedding-config.json`。
3. 证据：`docs\evidence\D38\{probe,ddl,startup,wiring,index,retrieval,isolation,ask,runs,baseline,regression,usage,security,resilience,special-cases,dataset,iteration_records}\`。
4. 文档：`docs\proj_docs\D38-01-…全量信息与操作.md`、`D38-02-…技术文档说明与实践指南.md`。
5. 状态：`docs\evidence\D38\iteration_records\D38_state_R01.md`。

### 6.2 Git 规则

- 提交范围：新源码、one_ 脚本与评测器**源码**（temp_docs 被忽略，沿用 D37 R02 先例 `git add -f`，**classes/编译产物不提交**）、evidence/D38、proj_docs 两份。
- 禁提交：surefire-reports、*.log、target/、classes/、任何密钥/口令/WorkspaceId 原值。
- 不 push；不改动 D37 已提交资产；提交信息 `D38 项目迭代R01 阿里百炼Embedding双轨接入（one_隔离）`。
- 提交前 `git diff --cached --stat` 核验范围；提交后 `git status` 干净。

---

## §7 状态文件模板（`docs\evidence\D38\iteration_records\D38_state_R01.md`）

```markdown
# D38_state_R01 —— 阿里百炼 Embedding 双轨接入

- 迭代/切片：D38 / R01
- 基线 commit：<执行时 HEAD>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17
- one_ 冻结参数：model=<G1> dims=<G2> maxBatch=<G3> normalized=<G4> canaryCosine=<G5>
- 阻塞项：B1 WorkspaceId=<已提供/缺失>；B2 模型名=<有效/无效+错误码>

## 门禁与用例落表
| 编号 | 状态(PASS/FAIL/BLOCKED/NOT RUN/N/A) | 证据路径 | 备注 |
|---|---|---|---|
| G1–G5 | … | probe\… | … |
| C01–C20 | … | … | … |

## 分级结论
L1=… L2=… L3=… L4=… L5=NOT RUN（单机）

## 预算实耗
calls=<n>/350 tokens=<n>/200000（来源：API usage 累计）

## 诚实声明
- 已执行并通过：…
- 已执行但失败：…
- 尚未执行（建议后续验证）：…
- N/A 项及原因：…

## 后续风险与 D39 候选
one_ SSE / 前端集成 / 混合检索 / 查询改写 / dashscope 原生通道 / 跨机 L5
```

---

## §8 官方溯源（引用须运行时复核，模型参数以探测为准）

- 《Alibaba Cloud Model Studio (Bailian) Documentation | 阿里云百炼大模型服务平台文档》：https://help.aliyun.com/zh/model-studio/ （入口页；**具体模型列表、维度、批量与 Token 上限以控制台与运行时探测为准，本任务书不锁定任何记忆值**）
- 《Compatibility of OpenAI with DashScope | OpenAI 兼容接口说明》：https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope （compatible-mode `/embeddings` 协议依据；执行时复核该页当前 URL 与参数）
- 《OpenAI API Reference — Embeddings | OpenAI 嵌入接口参考》：https://platform.openai.com/docs/api-reference/embeddings （请求/响应/usage 结构对照）
- 《Spring AI Reference — Embeddings API | Spring AI 嵌入 API 参考》：https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html （EmbeddingModel 接口契约，dimensions() 默认行为须实读 1.1.8 源码核验）
- 《pgvector — Open-source vector similarity search for Postgres | pgvector 官方仓库》：https://github.com/pgvector/pgvector （HNSW vector≤2000 维 / halfvec≤4096 维上限以该仓库 README 与本机 0.8.7 实测为准）
- 本地权威依据：D37 冻结基线 `docs\evidence\D37\baseline\v1.0\`、冻结数据集 `docs\evidence\D37\dataset\`、D37 任务书 `docs\temp_docs\D37\D37-项目迭代任务书Prompt-DB增强版-RAG评测脚本与基线.md`（体裁与口径范本）。

> 溯源纪律：不得伪造文档地址/源码内容/测试结果/版本信息；无法确认的信息明确标注「待运行时核验」。本任务书所有「实测值」均来自编制时对本地仓库的真实扫描，执行时若发现漂移，以执行时实读为准并在状态文件记录差异。