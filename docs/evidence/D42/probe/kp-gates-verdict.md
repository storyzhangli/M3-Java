# D42 P0 探测门禁裁决留痕（KP1–KP4）

编制时间：2026-11-25（实验执行之前）。骨架基线 a086fa2，工作树当时干净。

## KP1 能力与挂点（PASS）

- javap 实证 dashscope-sdk-java 2.23.6（D:\SoftwareInstall\Apache\repository\com\alibaba\dashscope-sdk-java\2.23.6\）：
  - `TextReRank`：构造 ()/(apiKey)/(apiKey,baseUrl)/(apiKey,baseUrl,ConnectionOptions)；`TextReRankResult call(TextReRankParam) throws ApiException, NoApiKeyException, InputRequiredException`
  - `TextReRank$Models.GTE_RERANK_V2 = "gte-rerank-v2"`（javap -constants 实证，位于内部类而非 common.Models——任务书写正，位置细化）
  - `TextReRankParam` extends HalfDuplexServiceParam：builder{query, documents(Collection), topN, returnDocuments, instruct}；`validate() throws InputRequiredException`
  - `TextReRankResult`：getRequestId/getUsage/getOutput/**getStatusCode/getCode/getMessage**（防御契约：非 2xx 可能以结果对象而非异常返回，适配器双形态都处理）
  - `TextReRankUsage.getTotalTokens() → Integer`
  - `TextReRankOutput.getResults() → List<Result{getIndex(), getRelevanceScore(), getDocument().getText()}>`
- 挂点复核：RagOrchestrator.ask L83-85 FAILED 短路之后、L88-89 上下文组装之前（唯一挂点，D1 一致）。
- 检索结果载体：`RagRetrievalResult.matches = List<SimilarityMatchView>`，含 chunkId/content/documentId/similarity/distance → 重排输入输出类型闭合（无需新增查询）。
- 权限语义：RagRetrievalService ObjectProvider 预门禁在 try 外（fail-closed 4030）→ rerank 挂点位于其之后，只重排已过滤结果（H7 成立）。
- 端点盘点：R01 零新增端点（rerank 仅经配置装配）；既有治理面端点不触碰。
- 本地分词能力：hybrid/keyword/KeywordTokenizer 存在但依赖 hybridProperties Bean 且 hybrid 默认不装配 → P1.3 裁决本地词法精排用**独立内联分词**（UTF-16 char bigram + ASCII 词归一，确定性，不复用 hybrid Bean）。

## KP2 存储盘点 + seeding（PASS，判据经裁决修正——见 rerank-protocol.json.corpus.adjudication）

- 盘点（seeding 前）：PG d42_vector_store 存在且 0 行；MySQL 无任何 d42_ 表（与任务书 §1.1"存在"预存假设不符 → D-EX3）。
- 前置修复：执行 d42_schema.sql 五表（D41 同构改名，×2 幂等零变化，evidence\D42\probe\kp2-ddl-run1/2.txt）。
- seeding：d42_index-corpus.ps1 经既有管线（注册核验→DELETE→INDEX→VERIFY）3 文档 8 片段，d42_vector_store=8 行（kp2-seed-run2.txt / kp2-inventory-postseed.txt）。
- 第一轮 DELETE code=5000 根因=d42_document_sync 表缺失（删除管线标记事务硬依赖）；建表后 DELETE 200 removed=3/3/2 收敛。
- **指纹裁决**：ID 面指纹=2cf619bd…（≠前代 10fc69d5…）；根因=D42 骨架 `StableChunkIdGenerator.NAMESPACE` 尾段 ...6d42 的模块隔离设计（D41=...6d41，源码 Compare-Object 实证）。内容面（canonical 去 chunkId 首列）两代表 8 行逐字段一致，内容指纹相等 = **c377bb2f…** → 语料字节一致获证，CORPUS_DRIFT（熔断#2）语义不成立。KP2 通过判据修正冻结于 protocol：内容面相等 + ID 面冻结为新值。
- 评测等价性：Evaluator logicalChunkId 按"内容+documentId"映射（Corpus.java 实证），不依赖 uuid → C05 质量投影列不受代际 id 影响；QA-001 uuid 锚换为 D42 代际 d19a82c3…（similarity 锚 0.37974774837493896 不变，容差 1e-9）。
- 遗留冻结：knowledge_document=10、chunk_index=32（orphan 24）、vector_index=7、DOC-* 3 行零触碰。

## KP3 冻结资产核验（PASS）

- 数据集 SHA256 前 12=75CC8D83CFB2 ✓；**行数实测 20（任务书 60 → D-EX1 编制误差，实测为准）**。
- baseline.csv：**20 行 17 列**（任务书 60 行 16 列 → D-EX1）；returnedLogicalIds 有序列存在 ✓。
- 评测器 9 源全量 javac 重编 EXIT=0；SelfTest **PASS=75 FAIL=0**（evidence\D42\probe\kp3-selftest.txt）✓ 零改动。

## KP4 实验协议与决策门禁冻结（PASS）

- `rerank-protocol.json` 冻结先于一切实验执行：臂 A0/A1/A2/A3、检索参数 topK=5/threshold=0.0/warmup3/rounds3、排序指标公式（MRR@5/nDCG@5 二值/hit@1/首个相关位次，真值=expectedChunkIds）、**决策阈值**（ΔMRR@5≥+0.05 ∧ ΔnDCG@5≥+0.05 ∧ hit@1 不降 ∧ local p95≤300ms ∧ remote p95≤1500ms ∧ fail-open≤1% ∧ 远程千问费用≤¥3.0）、延迟口径（nanoTime、warmup 排除、最近秩 p95）、异常码表 E1–E5、三重帽（call≤400/token≤2M/¥5；A2 预估 120 call）、截断规则（4000/30000、tokens≈chars/1.3 估算口径冻结）、指纹与偏差清单。
- A2 门禁前提实测：DASHSCOPE_API_KEY User 级存在（len=116，不回显）→ 远程臂具备执行条件，仍须 RERANK_REMOTE_ENABLED=true 显式注入。

## 基线复核补充（P0a）

- git grep rerank 命中仅 pom 模块名；main java=340；编译 EXIT=0；PG/MySQL 连通 True；RagOrchestrator/RagRetrievalService/SimilarityMatchView/ModelExceptionClassifier/RagProperties(app.rag)/adapter.ChatModelPort 范式全文精读完毕。
