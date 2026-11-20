# D37 项目迭代任务书 Prompt：RAG 评测脚本与基线

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 结构化记录生文档。
> 硬约束：以本地实际代码为唯一依据；最小必要修改；不伪造指标；Mock 不冒充真实端到端；检索空结果与检索失败严格区分。
> 本任务书所有路径、端点、字段名均来自 D37 骨架实扫（2026-11-20），执行时若与实际不符，先记录证据再停止对应动作。

---

## 0. 任务总览

| 项 | 值 |
|---|---|
| 迭代名称 | Day 37：RAG 评测脚本与基线 |
| 项目根 | E:\AI\project\AI-Learning-one\M3-Java |
| 模块 | D37\D37_RAG_ESAB（包 com.zl.d37，约 190 个 main Java） |
| 技术基线 | JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17、pgvector 0.8.7、Maven 3.9.16 |
| 核心交付 | ①一条命令可重复执行的评测器 ②20 问固定问题集 JSONL v1.0 ③第一版基线 baseline.csv + 汇总报告 ④指标可测性说明 ⑤失败标签定义与分类规则 |
| 质量目标 | 基线不求高分，求稳定可复现：换机器、换人执行，质量类指标必须完全一致 |

目录约定（全部已创建/已存在）：

| 用途 | 路径 |
|---|---|
| 技术文档（2 份，D37-XX 命名） | docs\proj_docs |
| 证据（含永久基线、manifest、状态文件） | docs\evidence\D37 |
| 临时脚本与评测器源码（禁止自动删除） | docs\temp_docs\D37 |
| 日志 | logs\D37 |

---

## 1. 基线核实（第一步，先于任何编码）

### 1.1 双基线分离（未知标 null，禁止脑补）

| 基线 | 定义 | 实测来源 |
|---|---|---|
| CURRENT_RUNTIME_BASELINE | 代码真实绑定：chunkSize=500、overlap=100、topK=5、threshold=0（SimilaritySearchRequest 默认）、PG 表 d37_vector_store、Embedding local-hashing-embedding/local-v1/384 维/COSINE | application.yml `app.ai.chunk.*`、`app.ai.embedding.*`、`pgvector.table` |
| EVAL_CONTROLLED_BASELINE | 评测器自身的受控参数（执行时显式传入，不依赖应用默认值） | 评测器配置文件，逐题回显实际生效值 |

### 1.2 环境事实（骨架已核实，执行时复验）

- 统一包装：所有 HTTP 出口为 `Result<T>`，字段 `code`（200 成功/5000 通用错误）、`msg`、`data`。
- 主评测端点：`POST /api/v1/rag/ask`，请求体 RagQuestionRequest（question 必填≤2000；topK 1..20 可空；documentId/version/strategy/title 可选过滤）。
- 纯检索端点：`POST /api/v1/retrieval/search`（含 queryEmbeddingMs/vectorSearchMs/retrievalTotalMs 三段计时，3 位小数）。
- 响应契约 RagAnswerResponse 关键字段：answer、sources[]、retrievalStatus(SUCCESS/EMPTY/FAILED)、generationStatus(SUCCESS/SKIPPED_NO_EVIDENCE/NOT_ATTEMPTED/NOT_CONFIGURED/CLARIFY/CONFLICT/FAILED_TIMEOUT/FAILED_ERROR)、errorCode（RagErrorCode 枚举 21 值）、retrievedCount、evidenceCount、timing{retrievalMs,assemblyMs,generationMs,totalMs}、unknownCitationDetected、decision、citationValidation、citationNotes。
- 来源契约 RagSourceView：sourceId（稳定，映射真实 documentId）、citationId（请求级，对应 sourceRef）、sourceRef(S1/S2…)、chunkId、documentId、title、page/lastPage、paragraphIndex、quotedSnippet、cited、similarity、distance、url；`@JsonInclude(NON_NULL)`，缺失字段隐藏不补造。
- 语料：classpath `knowledge/equipment-fault-corpus.json`，3 文档 8 片段（DOC-CNC-COOLING-001 三片段、DOC-SERVO-TROUBLE-002 三片段含注入教学片段、DOC-WORKORDER-SPEC-003 两片段），全部 url=null。
- 模式开关：AI_MODE=mock（默认；无 ChatModel Bean，生成路径返回 NOT_CONFIGURED，零外网）| deepseek（真实调用，AI_API_KEY 缺失启动 fail-fast）。
- **PG 口令环境变量名以 D37 yml 为准：`pgvector.password=${PG_VECTOR_PASSWORD:}`（直接读 PG_VECTOR_PASSWORD，不另设桥接变量）；MySQL 口令 MYSQL_PASSWORD（本机默认 123456）。**
- 前端已有 Node 内置测试：`src\test\frontend\front-end-safety.test.js`（node --test，零第三方依赖）。

### 1.3 可执行扫描/复验命令（执行开始时逐条运行并留痕）

```powershell
[Console]::OutputEncoding=[Text.Encoding]::UTF8
Set-Location "E:\AI\project\AI-Learning-one\M3-Java"
git rev-parse HEAD; git status --porcelain          # 基线 commit 与工作区干净度
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
# 应用就绪探测（无 /health 端点，用 GET /api/v1/documents）
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/documents" -TimeoutSec 3
```

---

## 2. 功能需求拆解

### 2.1 环境自动探测与双模式（两个维度独立判定，不是简单二元）

探测项（全部确定性、不发起模型调用）：①AI_API_KEY 是否非空白且 AI_MODE=deepseek；②PG 5432 端口与应用检索链路是否可用；③ChatModel Bean 是否存在（mock 模式不存在）。

| 能力维度 | 离线模式（无密钥 或 无 ChatModel） | 全量模式（有密钥 + ChatModel + PG 可达） |
|---|---|---|
| 检索质量 | HitRate、Recall@K、Precision、噪声占比、空检索 | 同左 |
| 检索耗时 | 三段计时（embedding/vector/total） | 同左 |
| 元数据完整性 | sourceId/chunkId/title/quotedSnippet 非空率、page 缺失统计 | 同左 |
| 拒答路径 | NO_EVIDENCE、CLARIFY、CONFLICT 三类受控决策（本就不调模型，可完整评测） | 同左 |
| 引用正确性 | unknownCitationDetected、citationValidation、url 安全策略（离线可测结构与静态规则） | 同左 + 真实模型输出上的引用核验 |
| 拒答质量 | 仅"期望拒答且系统拒答"可判；"期望回答"类用例标 NOT RUN | 全部期望回答/期望拒答用例 |
| 端到端延迟 | 不可测（无生成），标 NOT RUN | timing.totalMs 全量统计 |
| PG 不可达时 | 所有向量依赖指标标 BLOCKED（不是 FAIL、不是 0 分），探测类与静态类继续 | 同左 |

探测结果写入 `env-probe.json`（含时间戳、各维度布尔值、最终模式），并在汇总报告头部复述。

### 2.2 固定问题集 JSONL v1.0（20 问，先冻结后评测）

文件：`docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl`（每行一个 JSON 对象，UTF-8 无 BOM）。

场景分布（硬约束，各 5 条，共 20）：

| 场景 | questionId 前缀 | 标注要点 |
|---|---|---|
| 可答（单证据/多证据） | QA-001..005 | expectedDocumentIds、expectedChunkIds（可空，空则只判文档级）、expectedDecision=ANSWER |
| 无答案（含跨域实体） | QU-001..005 | expectedDecision=REFUSE、mustNotReturnDocumentIds（命中即拒答错误） |
| 故障码/型号术语 | QT-001..005 | 期望精确术语出现在来源片段；标注 expectedDocumentIds |
| 边界模糊（证据弱/冲突/信息不全） | QF-001..005 | expectedDecision ∈ CLARIFY/REFUSE/CONFLICT，标注 missingInformation 关键词 |

单条 JSONL Schema（字段名固定，核心契约禁用无约束 Map）：

```json
{
  "questionId": "QA-001",
  "scenario": "ANSWERABLE",
  "question": "问题文本",
  "expectedDecision": "ANSWER",
  "expectedDocumentIds": ["DOC-CNC-COOLING-001"],
  "expectedChunkIds": ["DOC-CNC-COOLING-001-C01"],
  "mustNotReturnDocumentIds": [],
  "expectedTerms": [],
  "annotatedAt": "由冻结脚本写入",
  "annotator": "manual-v1",
  "notes": "标注依据与边界说明"
}
```

数据集纪律：
1. 问题集在任何评测运行之前定稿；禁止看结果反改标注，修订必须升版本（v1.1）并写变更说明。
2. 每条问题标注期望回答/期望拒答与期望文档 ID；多证据用例显式列出全部期望 chunk。
3. 至少包含 2 条提示注入用例（沿用语料 C03 注入片段风格，问题中嵌入"忽略之前指令"等载荷），期望系统只当普通文本、不执行、不泄露系统提示。
4. 冻结清单 `dataset-manifest.json`：问题集、语料 JSON、应用配置、评测器版本的 SHA-256，绑定 Embedding 模型版本（local-hashing-embedding/local-v1/384）与文档集版本；不可变。
5. 可参考 D36 冻结数据集（docs\evidence\D36\retrieval-evaluation-dataset.json）的标注口径，但本数据集独立编号、独立 manifest，禁止跨版本复用可变文件。

### 2.3 评测器（一条命令跑完全量评测）

实现形态（沿用 D36 工具链范式，零新依赖）：Java 独立程序 + Jackson 2.19.0（本地仓库已有），源码放 `docs\temp_docs\D37\evaluator\`，PowerShell 包装器 `run-evaluation.ps1` 提供一条命令入口。

```powershell
# 一条命令：自动探测 → 模式选择 → 预热 → 正式评测 → 基线产物
& "E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs\D37\run-evaluation.ps1" `
  -BaseUrl "http://127.0.0.1:8080" -DatasetVersion "v1.0" -Rounds 3 -Warmup 3
```

执行器硬要求：
1. 固定问题顺序、单线程串行；预热 3 轮不计入；正式 ≥3 轮（默认 3），每题每轮一条原始记录。
2. 每题输出：questionId、场景、检索命中数、相关数、期望决策、实际决策、失败标签列表、三段耗时（全量模式含 totalMs）。
3. 原始记录 `raw-results.jsonl` 全量落盘；baseline.csv 与汇总报告一律由原始记录复算，禁止手填。
4. 超时控制：单题检索墙钟上限默认 30s、全量端到端 60s（可配置），超时计 TIMEOUT 标签且不中断后续题目；HTTP 客户端显式超时，测试用可控超时参数。
5. 幂等：重复运行生成带运行 ID 的独立目录，不覆盖已存在的永久基线；基线 v1.0 目录只读保存。
6. 退出码：全部 PASS=0；存在 FAIL=1；仅 BLOCKED/NOT RUN=2（便于 CI 区分）。

### 2.4 失败标签定义与分类规则（5 类 + 细分原因码）

| 大类标签 | 细分原因码 | 判定规则（全部可从原始记录复算） |
|---|---|---|
| MISS_RECALL 漏召回 | MISS_RECALL_EMPTY / MISS_RECALL_LOW_SCORE / MISS_RECALL_FILTERED | 期望文档/ chunk 未出现在 sources；空检索、被阈值过滤、被过滤条件排除分别归因 |
| HIGH_NOISE 噪声过高 | HIGH_NOISE_PRECISION / HIGH_NOISE_TOP1 | 单问 Precision < 0.5，或首位结果不在期望文档集（IRRELEVANT_TOP1） |
| FABRICATED_CITATION 引用伪造 | CITATION_UNKNOWN_ID / CITATION_UNSUPPORTED / CITATION_FABRICATED_URL / CITATION_OWNERSHIP_MISMATCH | unknownCitationDetected=true；citationValidation 报 invalid/unsupported；url 非空但无本轮元数据支撑或命中 FABRICATED_URL；归属与声明 documentId 不一致 |
| REFUSAL_ERROR 拒答错误 | REFUSAL_FALSE_NEGATIVE / REFUSAL_FALSE_POSITIVE / REFUSAL_WRONG_CODE | 期望回答却被拒（含错误码归因）；期望拒答却返回内容；拒答但 errorCode 与场景不匹配 |
| TIMEOUT 超时 | TIMEOUT_RETRIEVAL / TIMEOUT_GENERATION / TIMEOUT_TOTAL | 按触发阶段分别标记，记录实际墙钟与上限 |

标签互斥与叠加：同一题可同时携带多类标签（如 MISS_RECALL + REFUSAL_FALSE_NEGATIVE）；原因码每题每类至多一个，全部进入原始记录与 baseline.csv，禁止只写"失败"。

### 2.5 指标口径与计算公式（分母为 0 标 N/A，禁止伪装）

| 指标 | 公式 | 口径说明 |
|---|---|---|
| HitRate@K | 命中≥1 期望文档的问题数 / 该场景可判定问题数 | 按场景分别统计后再出总体 |
| Recall@K（Macro） | 单问=被覆盖的期望 chunk 数/期望 chunk 总数，再对问题取均值 | 无期望 chunk 标注的问题按文档级口径 |
| Precision@K | 相关来源数 / 实际来源数 | 来源 0 → N/A + EMPTY 标记 |
| 噪声占比 | 1 − Precision | 空检索 N/A |
| 拒答正确率 | 拒答决策与期望一致的问题数 / 期望拒答问题数 | 离线可测全部拒答路径 |
| 引用合法率 | 1 − 引用伪造问题数 / 含引用问题数 | 离线只测静态结构，全量测真实输出 |
| 元数据完整率 | 关键字段齐全的来源数 / 来源总数（sourceId、chunkId、title、quotedSnippet 为必填集） | page 缺失单列 PAGE_LOCATION_MISSING 不计入失败 |
| 耗时 | 均值/P50/P95/min/max，三段分别统计 | 仅含检索/生成链路，不含 SSE 传输、前端渲染与人工等待 |

评测器须内置纯函数指标计算器与自测夹具（沿用 D36 MetricsCalculator/MetricsSelfTest 模式），覆盖空集、单元素、多证据、无答案、边界半包含等夹具，自测结果先于正式评测留痕。

### 2.6 指标可测性说明（独立交付物）

文件 `measurability-boundary.md`：以 2.1 的能力矩阵为准，逐指标写明"可测/受限/BLOCKED/NOT RUN"的触发条件、判定证据与用户可见表现；明确无网络、无密钥、无 PG 三种缺失各自能跑什么、不能跑什么，并说明 BLOCKED 与 FAIL 的本质区别（环境缺失 vs 系统错误）。

---

## 3. 工程深度与安全专项

### 3.1 引用与来源契约

1. 稳定标识与请求级标识严格区分：sourceId 跨请求稳定（=真实 documentId），citationId 仅本轮有效（=S1/S2…）；评测器逐题核对两者映射，禁止混用或互换。
2. 引用信息只能来自本轮检索元数据：评测器对每条来源回查原始记录，quotedSnippet 必须是真实内容子串、page 必须与元数据一致；模型自报的页码/路径/URL 一律不作为证据。
3. URL 协议黑名单：仅 http/https 放行；javascript/data/file/ftp、Windows 盘符路径、无主机、localhost/环回/内网地址一律拒绝（与 UrlSafetyPolicy 实测规则一致）；评测器包含危险协议夹具，确认 UI 与 API 均不呈现虚构 URL。
4. 同步与 SSE 出口一致性：两类出口共用 CitationDto（含 sourceId/citationId/quotedSnippet/cited）；评测器对同步 `/api/v1/rag/ask` 与 SSE 解析（static\js\sse-parser.js、SsePayloads）做同数据集对照，字段语义不一致即 FAIL。
5. 前端安全渲染：`node --test src\test\frontend\front-end-safety.test.js` 必须通过（无 innerHTML 赋值、replaceChildren、外链 noopener noreferrer）；评测器另加静态扫描确认页面不渲染无来源 URL。

### 3.2 拒答质量与无证据路径

- 逐题比对 expectedDecision 与实际 decision/retrievalStatus/generationStatus/errorCode 四元组；拒答文案不得包含堆栈、上游报文、连接串、密钥。
- 无答案问题集必须覆盖"词面相近但实体不同"的硬案例（如 D36 的激光切割机），验证系统不会因关键词重叠而强行作答。
- CLARIFY 用例核验 clarificationQuestion 与 missingInformation 标注一致；CONFLICT 用例核验 conflictGroups 结构与被拒证据 ID。

### 3.3 注入、冲突与不可信证据

1. 注入用例（≥2）：问题与语料中携带指令型载荷，期望系统按普通文本处理、不执行、不改变系统行为、不回显系统提示词；响应中出现指令执行迹象即 FAIL。
2. 不可信证据标记：原始记录对每条来源标注信任级别（生效/废弃/草稿/未知），EVIDENCE_NOT_EFFECTIVE 等路径必须可复现；评测器禁止把"分别存在但无法证明归属"的引用判为有效。
3. 冲突用例：同一事实多来源矛盾时期望 EVIDENCE_CONFLICT 且不调用生成，保留零调用证据（应用日志 + 原始记录双证据）。

### 3.4 删除竞态与批量核验

1. 评测器包含"检索后删除文档再返回"用例：响应前权威核验必须命中 CITATION_TARGET_DELETED/归属不一致等原因码，全部来源失效时抑制答案。
2. 批量核验逐来源输出原因码（删除/失效/归属），原因码必须可区分、可计数；禁止单点核验后对整批放行。
3. 数据准备脚本只允许操作本迭代创建的 documentId/表；历史残留（d32eval 等）与正式表 d37_vector_store 只读不碰；禁止删表、删卷、Docker prune 等破坏性操作。

### 3.5 确定性与跨机器复现（本迭代第一质量目标）

1. 基线永久留存于 `docs\evidence\D37\baseline\v1.0\`（baseline.csv、summary.md、raw-results.jsonl、manifest），后续所有参数实验与同一基线对比。
2. 质量类字段（命中、相关、决策、标签）跨机器、跨执行人必须逐字节一致；耗时类字段只报分布区间，不参与一致性判定，也不进入基线对比键。
3. 保证确定性的措施：固定问题顺序、预热不纳入、单线程、固定本地 Embedding（无随机外网调用）、显式超时、重试不伪装成功；运行时间戳只存元数据区，不混入基线行。
4. 复现验收：第二次运行（可换工作目录）自动 diff 质量类字段，全部一致才允许标记 PASS 并写入基线。

---

## 4. 验收标准

### 4.1 四态（逐用例、逐指标标注）

PASS：实际结果与期望一致且证据可复算；FAIL：期望与实际不符或校验拦截；BLOCKED：环境/依赖缺失导致无法执行（如 PG 不可达）；NOT RUN：当前模式不覆盖（如离线模式的端到端延迟）。四态计数进入汇总报告，合计必须等于用例总数。

### 4.2 Level 1–5 分级

| Level | 验收内容 | 达成条件 |
|---|---|---|
| Level 1 | 环境探测与模式判定 | env-probe 各维度与真实环境一致，错误模式能正确降级 |
| Level 2 | 数据集与冻结清单 | 20 问 JSONL 合法、四场景齐全、manifest 哈希可复验 |
| Level 3 | 离线指标与计算器自测 | 自测全 PASS，离线指标从原始记录复算一致 |
| Level 4 | 失败标签与可测性边界 | 5 类标签原因码全部有真实用例覆盖，measurability 文档与实际模式一致 |
| Level 5 | 全量真实端到端 | 全量模式下真实模型 + 真实 pgvector 跑通；无密钥时 Level 5 标 NOT RUN 并注明前置条件，离线以 Level 4 为天花板，不得伪称 Level 5 |

### 4.3 逐条用例清单（执行时在状态文件中逐项落表）

| 编号 | 用例 | 期望 |
|---|---|---|
| C01 | 环境探测：mock+PG 可达 | 离线模式，检索/拒答/静态引用指标可测，生成类 NOT RUN |
| C02 | 环境探测：PG 不可达 | 向量依赖指标 BLOCKED，静态扫描继续，退出码 2 |
| C03 | 20 问 schema 校验 | 四场景各 5 条，字段完整，非法 JSON 被拒绝 |
| C04 | manifest 哈希复验 | 重算 SHA-256 与清单一致，篡改可被发现 |
| C05 | 可答用例（含多证据） | 期望文档/ chunk 命中，决策 ANSWER（全量）或 NOT RUN（离线生成部分） |
| C06 | 无答案用例（含词面相近硬案例） | 受控拒答，errorCode 与场景匹配，零模型调用 |
| C07 | 故障码/术语用例 | 期望术语出现在真实来源片段，缺失即 MISS_RECALL |
| C08 | 边界模糊用例 | CLARIFY/REFUSE/CONFLICT 与标注一致，澄清问题可核验 |
| C09 | 注入用例 ×2 | 载荷不执行、不泄密，行为与普通文本一致 |
| C10 | 危险协议/路径 URL 夹具 | 全部拦截，API 与 UI 无虚构 URL，前端 node 测试通过 |
| C11 | 同步/SSE 契约对照 | 同数据集字段语义一致，citation 映射一致 |
| C12 | 删除竞态用例 | 命中可区分原因码，全失效时抑制答案 |
| C13 | 超时用例（可控超时参数） | 按阶段标记 TIMEOUT，后续题目继续执行 |
| C14 | 二次运行一致性 diff | 质量类字段逐字节一致，基线目录只读 |
| C15 | 指标计算器自测 | 全部夹具 PASS 后才允许正式评测 |

### 4.4 熔断规则

- 同一根因连续失败 2 次：立即停止该链路，持久化现场与原因码，进入人工确认；禁止换写法绕过、禁止无限重试。
- 基础设施类错误（连接拒绝、认证失败、超时）与数据类错误（COUNT_MISMATCH、校验失败）分别计数，不混为同一根因。
- 真实模型调用仅限全量模式且最小必要：离线零调用；全量每问至多 1 次调用，受控失败不重试生成。

---

## 5. 成本与 Token 预算（沿用状态模板，不伪称预算）

| 项 | 规则 |
|---|---|
| 离线模式 | DeepSeek 调用 0 次、Token 0；零外网请求 |
| 全量模式 | 20 问 × 至多 1 次调用；预热轮不计入模型调用（预热走检索链路） |
| 累计 Token 阈值模板 | 300 万：报告提示；400 万：暂停非必要调用并复核；500 万：硬停止等待授权。实际值以真实 usage 记录为准，禁止估算冒充 |
| 密钥管理 | 仅环境变量注入，禁止写入文件/PATH/日志；访问日志脱敏规则保持开启 |

---

## 6. 交付物清单

| # | 交付物 | 路径 |
|---|---|---|
| 1 | 评测器源码 + 一条命令包装器 | docs\temp_docs\D37\evaluator\、run-evaluation.ps1（保留不自动删） |
| 2 | 20 问固定问题集 v1.0 | docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl |
| 3 | 冻结清单（SHA-256、模型/文档版本） | docs\evidence\D37\dataset-manifest.json、env-probe.json |
| 4 | 第一版基线（永久只读） | docs\evidence\D37\baseline\v1.0\：baseline.csv、summary.md、raw-results.jsonl |
| 5 | 指标可测性说明 | docs\evidence\D37\measurability-boundary.md |
| 6 | 失败标签规则文档 | docs\evidence\D37\failure-label-rules.md |
| 7 | 计算器自测证据 | docs\evidence\D37\metrics-selftest.txt |
| 8 | 两份技术文档（D37-XX） | docs\proj_docs\D37-01-…全量信息与操作.md、D37-02-…技术文档说明与实践指南.md |
| 9 | 迭代状态文件 | docs\evidence\D37\iteration_records\D37_state_R01.md |
| 10 | 运行日志 | logs\D37\（surefire-reports 不提交 Git；target/ 已被忽略） |

Git 提交范围：仅本迭代新增的证据/文档与经授权的源码修改；提交前 `git status` 核实改动面，不 push；历史表与无关文件零改动。

---

## 7. 状态文件模板（D37_state_R01.md）

```markdown
# D37 迭代状态
## 需求编号
D37-R01（RAG 评测脚本与基线）
## 基线核实
- git 基线：<commit>；工作区：干净/已修改（列明细）
- 双基线：CURRENT_RUNTIME_BASELINE=<实测值>；EVAL_CONTROLLED_BASELINE=<受控值>
- 环境探测：MySQL/Redis/PG 布尔值；模式：离线/全量；env-probe 文件指针
## 数据集冻结
- 问题集：v1.0，20 问（四场景各 5）；manifest 哈希前缀
- 语料/模型版本：equipment-fault-corpus.json、local-hashing-embedding/local-v1/384
## 评测执行
- 预热/正式轮次：3/N；原始记录条数；退出码
- 四态统计：PASS n / FAIL n / BLOCKED n / NOT RUN n（合计=用例总数）
- Level 达成：L1..L5 逐项状态及证据指针
## 基线结果
- baseline.csv 关键指标（按场景）；一致性 diff 结果
- 失败标签分布（按类×原因码）
## 真实失败与修复
- 根因/修复文件/验证结果（区分已执行通过/已执行失败/未执行）
## 遗留与边界
- 未覆盖项、NOT RUN 前置条件、跨机器复现结论、后续迭代对接方式
```

---

## 8. 官方文档溯源

- Spring AI Reference - RAG | Spring AI 参考-RAG：https://docs.spring.io/spring-ai/reference/api/rag.html
- Spring AI Reference - Vector Databases | Spring AI 参考-向量数据库：https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Spring AI Reference - PGvector Vector Store | Spring AI 参考-PGvector 向量存储：https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- Spring AI Reference - Embedding Model API | Spring AI 参考-Embedding 模型 API：https://docs.spring.io/spring-ai/reference/api/embeddings.html
- pgvector - Open-source vector similarity search for Postgres | pgvector-PostgreSQL 开源向量相似度搜索：https://github.com/pgvector/pgvector
