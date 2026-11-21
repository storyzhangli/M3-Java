# D38-01-阿里百炼Embedding双轨接入-项目迭代全量信息与操作

> 切片：D38 / R01 ｜ 基线 commit：e05311f（D38 骨架）｜ 执行日期：2026-11-21
> 配套技术文档：`D38-02-阿里百炼Embedding双轨接入-技术文档说明与实践指南.md`
> 状态文件：`docs/evidence/D38/iteration_records/D38_state_R01.md`

## 一、概述

本迭代在 D38 模块实现**阿里百炼 Embedding 双轨接入**：

- **local 轨零行为变更**：既有本地哈希词面 Embedding（384 维）+ pgvector 检索/入库/ask 全链路保持逐字节不变（C07 证明）。
- **one_ 轨全新隔离**：新增百炼 `qwen3.7-text-embedding`（1024 维）为独立 one_ 轨，全套新前缀表/脚本/Bean/配置/端点，与 local 轨永不混用向量空间。
- **评测结论（诚实）**：one_ 正式评测 run1 语义质量全稳定（20 问三轮决策一致），但严格排序字节一致门禁因**远端非确定性**（QU-005 近似平局排序翻转）触发熔断 → C12/C13 如实 BLOCKED，**不冻结假基线**。用户 2026-11-21 裁决接受如实 BLOCKED（任务书默认终态）。
- **既有遗留清理（用户授权）**：D30 任务书文档硬编码真实 PG 密码已脱敏；454 个历史 surefire-reports 移出 Git 跟踪并加 .gitignore 规则。

## 二、技术基线

| 项 | 值 | 来源 |
|---|---|---|
| JDK | 21（D:\SoftwareInstall\JDK\JDK21） | 模块 pom |
| Spring Boot | 3.5.16 | 父 POM |
| Spring AI | 1.1.8 | 父 POM |
| MyBatis-Plus | 3.5.17 | 模块 pom |
| Maven | 3.9.16（D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16） | 用户约定 |
| 本地仓库 | D:\SoftwareInstall\Apache\repository | 用户约定 |
| PostgreSQL | 192.168.200.153:5432/spring_ai，pgvector 0.8.7 | P2 catalog 核验 |
| MySQL | 127.0.0.1:3306/ai_db | 共享语料登记 |

**one_ 轨冻结参数**（P0 探测 G1–G5，`probe/one-embedding-params.json`）：

| 参数 | 值 | 门禁 |
|---|---|---|
| model | qwen3.7-text-embedding | G1 PASS（服务端回显同名） |
| modelVersion | remote-v1（配置级标签） | 服务端未暴露独立版本号 |
| dimensions | 1024 | G2 PASS（≤2000 HNSW vector 红线） |
| maxBatch | 16 | G3 PASS（25→400 invalid_parameter_error） |
| normalized | false（L2 范数 0.0218） | G4 记录事实，COSINE 不敏感不阻断 |
| canaryCosine | 1.0（单次观测） | G5 PASS；抖动具输入依赖性，见 §八 |
| distanceMetric | COSINE | 与 local 轨口径一致 |
| vectorSpaceId | bailian\|qwen3.7-text-embedding\|remote-v1\|1024 | 四一致 |

**local 轨参数**（不变）：local-hashing-embedding / local-v1 / 384 维 / COSINE / vectorSpaceId=local\|local-hashing-embedding\|local-v1\|384。

## 三、交付物清单与位置

### 3.1 代码（`D38/D38_QWE_MROQRE/src/main/java/com/zl/d38/one/`）

| 子包 | 类 | 职责 |
|---|---|---|
| config | OneEmbeddingProperties | app.one.embedding 配置 + @PostConstruct fail-fast validate |
| config | OnePgVectorProperties | app.one.pgvector（table 强制 one_ 前缀） |
| config | OneEmbeddingConfiguration | oneEmbeddingModel（@Qualifier localGuard 防静默换轨） |
| config | OnePgVectorStoreConfiguration | onePgVectorStore（@Qualifier oneEmbeddingModel） |
| client | OneBailianEmbeddingClient | JDK HttpClient+Jackson 手写客户端，重试分类/退避/usage/脱敏 |
| client | OneEmbeddingException | 受控异常（httpStatus/errorCode/retryable） |
| embedding | OneRemoteEmbeddingModel | extends AbstractEmbeddingModel，dimensions() 零网络 |
| index/po | OneDocumentVectorIndexPO、OneDocumentChunkIndexPO | 带 vectorSpaceId |
| index/mapper | OneDocumentVectorIndexMapper、OneDocumentChunkIndexMapper | MyBatis-Plus |
| index | OneVectorIndexStateTx、OneDocumentVectorIndexService、OneVectorReconciliationService | 入库状态机/幂等/对账 |
| index/dto | OneVectorIndexRequest、OneVectorIndexResultView、OneReconciliationReportView | 入库契约 |
| retrieval | OneSimilaritySearchService | 独立检索实现，DTO 只读复用共享 |
| rag | OneRagRetrievalService、OneRagOrchestrator | 复制 local 流程仅换检索源，共享决策 Bean 零修改 |
| service | OneIndexCompatibilityService | 兼容性三态 |
| web | OneEmbeddingController、OneVectorIndexController、OneSimilaritySearchController、OneRagController | /api/v1/one/** 端点 |

**H1 唯一豁免改动**（仅 2 处 @Primary + 注释，git diff 核验）：
- `document/embedding/config/EmbeddingConfiguration.java`：localHashingEmbeddingModel 加 @Primary。
- `document/vectorstore/PgVectorStoreConfiguration.java`：pgVectorStore 加 @Primary。
- `src/main/resources/application.yml`：末尾追加 app.one.* 段（既有行未动）。
- `src/main/resources/db/schema-one-document-vector-index.sql`：one_ 两 MySQL 表 DDL。

### 3.2 脚本与评测器（`docs/temp_docs/D38/`，git add -f 提交源码）

| 文件 | 用途 |
|---|---|
| one_probe-embedding.ps1 / one_probe-semantic.ps1 | P0 探测 G1–G5 + 语义 sanity |
| one_ddl-apply.jsh / one_pg-catalog.jsh | P2 建表 + catalog 核验 |
| one_index-corpus.ps1 | P3 语料入库 + 幂等/strategy/reconcile |
| one_retrieval-ask.ps1 | P4 检索/ask/隔离/兼容性 |
| one_local-regression.ps1 / one_local-regression-config.json | C07 local 无回归 |
| one_evaluator/src/oneeval/*.java（8 源文件） | one_ 评测器（删 LocalEmbedding，三态后端，canary/预算门禁） |
| one_evaluator/one-evaluator-config.json | 评测配置（topK5/threshold0/warmup3/rounds3/预算1000） |
| one_run-evaluation.ps1 | 编译+SelfTest+两跑+diff 一键驱动 |
| one_special-fixtures.ps1 | C11 夹具 CONFLICT/生命周期 |
| one_fixture-cleanup.jsh | C18 两轨夹具残留核验 |
| resilience/OneRetryClassification.java | C16 本地桩受控注入（14 场景，零远程） |

### 3.3 证据（`docs/evidence/D38/`）

probe / ddl / startup / wiring / index / retrieval / isolation / ask / runs / regression / usage / security / resilience / special-cases / dataset / iteration_records / measurability-boundary.md。
**baseline/ 未冻结**（C13 BLOCKED，不产假基线）。

## 四、双轨架构与隔离契约（H1–H8）

| 契约 | 内容 | 取证 |
|---|---|---|
| H1 | local 零行为变更，唯一豁免 2 个 @Primary | wiring/diff-annotations.patch、regression/ |
| H2 | one_ 前缀隔离（表/包/Bean/配置/端点/脚本/评测器/基线目录） | §五命名清单 |
| H3 | 向量空间永不混用，跨空间复用被兼容性检查拒绝 | isolation/compatibility.json |
| H4 | 密钥仅 env 注入，Authorization/错误体脱敏 | security/secret-scan.txt、resilience/S7 |
| H5 | 探测先行，型号/维度/批量/归一化/确定性以运行时为准 | probe/ |
| H6 | 真实调用最小化，全程计数计 Token，超预算熔断 | usage/usage-summary.json |
| H7 | 不虚构结果，区分已执行通过/失败/未执行，N/A 写明原因 | measurability-boundary.md |
| H8 | 禁 Docker prune/删卷/DROP 既有表/覆盖 D37 基线，夹具用后清理 | ddl/UNCHANGED=true、special-cases/fixture-cleanup.txt |

## 五、one_ 资产命名清单

| 类别 | local 轨 | one_ 轨 |
|---|---|---|
| PG 表 | d38_vector_store vector(384) | one_d38_vector_store vector(1024) |
| MySQL 表 | document_vector_index / document_chunk_index | one_document_vector_index / one_document_chunk_index（+vector_space_id VARCHAR(160) NOT NULL） |
| Java 包 | com.zl.d38.document.* | com.zl.d38.one.* |
| 配置前缀 | app.ai.embedding / app.pgvector | app.one.embedding / app.one.pgvector |
| 端点前缀 | /api/v1/** | /api/v1/one/** |
| Bean | localHashingEmbeddingModel(@Primary) / pgVectorStore(@Primary) | oneEmbeddingModel / onePgVectorStore（全 @Qualifier） |
| 脚本 | （D37 既有） | one_*.ps1 / one_*.jsh |
| 评测器包 | d37eval | oneeval |
| 基线目录 | PGVECTOR / IN_MEMORY_LOCAL | ONE_PGVECTOR（本轮未冻结） |
| 共享表 | knowledge_document / knowledge_document_page（两轨共享，不改） | 同左 |

## 六、操作手册（可重复执行）

### 6.1 前置（密钥仅环境变量，禁硬编码）

```powershell
# User 级环境变量已配置：DASHSCOPE_API_KEY / BAILIAN_EMBEDDING_BASE_URL / PG_VECTOR_PASSWORD / MYSQL_PASSWORD
$env:JAVA_HOME = "D:\SoftwareInstall\JDK\JDK21"
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$mvn  = "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd"
```

### 6.2 启动 one_ 轨应用

```powershell
$env:ONE_EMBEDDING_ENABLED = "true"
$env:ONE_EMBEDDING_DIMENSIONS = "1024"          # P0 冻结值，必须注入
$env:AI_MODE = "mock"
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD','User')
$env:DASHSCOPE_API_KEY = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User')
$env:BAILIAN_EMBEDDING_BASE_URL = [Environment]::GetEnvironmentVariable('BAILIAN_EMBEDDING_BASE_URL','User')
& $mvn -f "$repo\D38\D38_QWE_MROQRE\pom.xml" spring-boot:run
# 关闭 one_ 轨：ONE_EMBEDDING_ENABLED=false（默认），one_ Bean 不装配，零远程调用
```

### 6.3 探测 / 入库 / 检索 / ask

```powershell
pwsh -File "$repo\docs\temp_docs\D38\one_probe-embedding.ps1"     # G1–G5
pwsh -File "$repo\docs\temp_docs\D38\one_index-corpus.ps1"        # 8/8 COMPLETED + 幂等 + strategy + reconcile
pwsh -File "$repo\docs\temp_docs\D38\one_retrieval-ask.ps1"       # 检索契约/隔离/兼容性/ask
# 端点：GET /api/v1/one/embeddings/usage（注意 embeddings 复数）
#       GET /api/v1/one/embeddings/model
#       POST /api/v1/one/vector-index/index
#       POST /api/v1/one/retrieval/search
#       POST /api/v1/one/rag/ask
```

### 6.4 评测（编译+SelfTest+两跑+diff）

```powershell
pwsh -File "$repo\docs\temp_docs\D38\one_run-evaluation.ps1"
# 会话注入 ONE_CALL_BUDGET=1000 / AI_MODE=mock；SelfTest 73 项全绿才正式跑
# 本轮 run1 触发 C12 熔断（QU-005 近似平局排序），run2 未执行，基线未冻结
```

### 6.5 夹具与清理 / local 回归 / 重试分类

```powershell
pwsh -File "$repo\docs\temp_docs\D38\one_special-fixtures.ps1"    # CONFLICT + 生命周期 REFUSE（~6 次调用）
& "$env:JAVA_HOME\bin\jshell.exe" --class-path <mysql-connector-j-9.7.0.jar> "$repo\docs\temp_docs\D38\one_fixture-cleanup.jsh"
pwsh -File "$repo\docs\temp_docs\D38\one_local-regression.ps1"    # D37 评测器只读，local 零远程
# C16：javac/java OneRetryClassification（本地桩，零远程，14/14 PASS）
```

### 6.6 编译与测试

```powershell
& $mvn -f "$repo\D38\pom.xml" -pl D38_QWE_MROQRE test            # BUILD SUCCESS（无 Java 测试，编译通过）
Push-Location "$repo\D38\D38_QWE_MROQRE"; node --test "src\test\frontend\front-end-safety.test.js"; Pop-Location  # 6/6
```

## 七、验收结果（C01–C20 与 L1–L5，真实执行）

| 编号 | 状态 | 证据 | 备注 |
|---|---|---|---|
| C01 | PASS | compile-test.txt | mvn test exit 0 BUILD SUCCESS；前端 node --test 6/6 |
| C02 | PASS | probe/ | G1–G5 全裁决，冻结参数脱敏 |
| C03 | N/A | probe/probe-log.txt | 模型有效，负向门禁不触发 |
| C04 | PASS | startup/ | 缺密钥/缺 dims fail-fast（exit 1 明确报错）；enabled=false 启动成功+one_ absent+结构性零远程 |
| C05 | PASS | ddl/ | one_ 表建成 vector(1024)+HNSW cosine；既有 4 表 UNCHANGED=true |
| C06 | PASS | wiring/ | local(@Primary)+one_ 两对 Bean；全 Qualifier；git diff 仅 2 注解（无 actuator，源码推导+运行时共存实证） |
| C07 | PASS | regression/ | QA-001 top1 与 D37 冻结值差≤1e-9；D37 评测器完整跑 diff 字节一致；SelfTest 75/75 |
| C08 | PASS | index/ | 8/8 COMPLETED；幂等短路零新增调用；strategy 切换稳定 ID 复现；orphan=0 missing=0 |
| C09 | PASS | retrieval/ | matches/total/vectorSpaceId/filtered/三段计时；越界/空白/超长拒绝；零向量拒绝 |
| C10 | PASS | isolation/ | local 探针 delta=0；metadata 空间分离；兼容性三态全中 |
| C11 | PASS | ask/、special-cases/ | QA-001→ANSWER+NOT_CONFIGURED 引用契约全符合；CONFLICT/EVIDENCE_CONFLICT；生命周期 REFUSE/NO_EVIDENCE rejected=1 |
| C12 | **BLOCKED** | runs/divergence-analysis.json | REPEAT_RESULT_DIVERGED：QU-005 近似平局排序被远端抖动翻转；20 问语义三轮全一致；用户裁决接受 BLOCKED |
| C13 | **BLOCKED** | baseline/（未冻结） | 依赖 C12；不冻结假基线（§4.3.5） |
| C14 | PASS | usage/usage-summary.json | 全程 514/15331 真实累计，跨进程汇总，未超预算，per-phase 齐全 |
| C15 | PASS | security/secret-scan.txt | 真实云密钥零命中；D30 密码脱敏；454 surefire 移出跟踪 |
| C16 | PASS | resilience/retry-classification.json | 本地桩受控注入 14/14，零远程 |
| C17 | PASS | special-cases/C17-*.txt | 前端 6/6；static/ 零改动；后端危险渲染零命中 |
| C18 | PASS | special-cases/fixture-cleanup.txt | FIXTURE_REMAINING=0；两轨夹具残留全 0；语料 3 文档/8 片段完好 |
| C19 | PASS | proj_docs/D38-01/02、iteration_records/ | 双文档+状态文件齐全，Mermaid Typora 兼容 |
| C20 | 见 §十一 | git/commit-stat.txt | 提交范围核验，不 push |

**分级**：L1=PASS（C01）｜ L2=PASS（C02/C04/C05/C06/C07/C08/C09/C10）｜ L3=BLOCKED（C12/C13 远端非确定性，如实标注）｜ L4=PASS（C14/C15/C16/C18）｜ L5=NOT RUN（单机）。

## 八、评测与基线结论（C12 熔断明细）

- **run1 完成**：probe2+canary2+warmup240+formal240=484 次/13424 tokens；canary 门禁 PASS（QA-001 排序逐位一致）；SelfTest 73/73。
- **语义全稳定**：全部 20 问三轮 decision/errorCode/generationStatus 完全一致。
- **分歧点**：仅 QU-005（噪声题"红烧肉做法"）rank2/rank3 两个无关片段排序翻转。
  - round1：DOC-WORKORDER-SPEC-003-C02 sim=0.19151336 vs DOC-CNC-COOLING-001-C02 sim=0.19148904，Δ=2.43e-5。
  - round2/3：两者翻转（CNC-C02 升 rank2）。
- **根因实锤**：同一存储片段 DOC-SERVO-TROUBLE-002-C03（三轮均 rank1）distance 三轮 0.76906/0.76914/0.76859，漂移 ~5.5e-4。存储向量固定、PG 8 行等效精确检索 → 漂移来自远端 query Embedding 浮点抖动（~1e-4 量级）。
- **风险面**：QF-004 最小间隙 2.3e-4 亦在抖动量级内，本轮未翻转属偶然。
- **裁决**：用户接受如实 BLOCKED（任务书默认）。不冻结假基线，分歧明细落 `runs/divergence-analysis.json`。
- **后续可选**（未采纳）：tie-band ε=1e-3 平局带投影修订 + 补跑，可在语义稳定前提下冻结基线；留待未来裁决。

## 九、成本与预算（C14）

| 阶段 | 调用 | Token | 通道 |
|---|---|---|---|
| P0 探测 | 9 | 746 | PowerShell 直连（不经应用计数器） |
| P3 入库 | 5 | 689 | 已停进程 |
| P4 检索/ask | 10 | 272 | 当前进程初始段 |
| 评测 run1 | 484 | 13424 | probe2+canary2+warmup240+formal240 |
| 夹具 | 6 | 200 | CONFLICT+生命周期 |
| C16 重试 | 0 | 0 | 本地桩受控注入 |
| C04 disabled | 0 | 0 | 结构性零远程 |
| local 回归 | 0 | 0 | 哈希零网络 |
| **全程合计** | **514** | **15331** | 跨进程手工汇总 |

预算 ONE_CALL_BUDGET=1000 / ONE_TOKEN_BUDGET=200000（用户裁决）；剩余 486 次/184669 tokens；**未超预算**。
run2 因熔断未执行，故实耗低于全规格两跑预估（968），514 为真实终值非估算。

## 十、既有遗留清理（用户 2026-11-21 授权全面清理）

1. **D30 密码泄漏**：`docs/proj_docs/D30 项目迭代任务书 Prompt｜PostgreSQL + pgvector.md` 第 144/158 行硬编码真实 PG 密码 → 脱敏为 `${PG_VECTOR_PASSWORD}` 占位 / `${PGVECTOR_DB_PASSWORD:}` 空默认（before=2 after=0）。
2. **surefire-reports**：454 个历史文件（D26–D37）`git rm --cached` 移出索引（本地保留 454=454）；.gitignore 新增 `**/surefire-reports/` 与 `*.dumpstream`。实测无 PG/DASHSCOPE 真实密钥，含 java.library.path 环境信息，按用户长期约束禁提交。
3. C15 复扫 PASS：真实云密钥零命中，surefire 未跟踪。

## 十一、Git（C20）

- 提交信息：`D38 项目迭代R01 阿里百炼Embedding双轨接入（one_隔离）`
- 范围：one_ 新源码 + 2 处 @Primary + application.yml 追加段 + DDL；one_ 脚本/评测器**源码**（temp_docs 被忽略，git add -f，classes/编译产物不提交）；evidence/D38；proj_docs 两份；D30 脱敏 + .gitignore + 454 surefire 删除（授权卫生修复）。
- 禁提交：surefire-reports（已移出）、*.log、target/、classes/、任何密钥原值。
- 不 push；不改动 D37 已提交资产（D30 脱敏为用户授权安全修复，非 D37 资产）。
- 核验：`git diff --cached --stat` + 提交后 `git status` 干净。详见 `git/commit-stat.txt`。

## 十二、官方文档溯源

| 标题（中英对应） | 官方链接 |
|---|---|
| 《Spring Boot Reference \| Spring Boot 参考文档》 | https://docs.spring.io/spring-boot/3.5/index.html |
| 《Spring Framework Core Reference \| Spring 框架核心参考》（@Primary/@Qualifier/Bean 装配） | https://docs.spring.io/spring-framework/reference/core.html |
| 《Spring AI Reference \| Spring AI 参考文档》（EmbeddingModel/VectorStore） | https://docs.spring.io/spring-ai/reference/ |
| 《pgvector README \| pgvector 说明》（HNSW/vector 类型/COSINE） | https://github.com/pgvector/pgvector |
| 《阿里云百炼 Model Studio \| Alibaba Cloud Model Studio》（OpenAI 兼容 Embedding） | https://help.aliyun.com/zh/model-studio/ |
| 《Java 21 HttpClient API \| Java 21 HttpClient 接口》 | https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html |

> 注：以上为官方入口链接；Spring AI/百炼深层 API 页以对应版本官方文档为准，未核实深层 URL 不伪造。