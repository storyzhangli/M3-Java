# D37 迭代状态

## 需求编号
D37-R01（RAG 评测脚本与基线）

## 基线核实
- git 基线：`27ab844a37001902470ce7124093ccdd8660387e`（"D37 项目基础骨架"）；工作区：迭代开始时干净，结束时仅新增本迭代文件（`docs/evidence/D37/`、`docs/proj_docs/D37-01/02`；`docs/temp_docs/` 与 `logs/D37/*.log` 按 .gitignore 忽略，保留磁盘不删）。
- 双基线：
  - CURRENT_RUNTIME_BASELINE（POM/yml/源码实读）：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17；chunk 500/overlap 100（UTF16_CODE_UNIT）；topK 默认 5（1..20）；threshold 默认 0.0（similarity 语义）；Embedding local-hashing-embedding/local-v1/384/COSINE/max-input 8192；PG 表 d37_vector_store（HNSW/COSINE_DISTANCE）；budgetUnit=CHARACTER/contextBudgetChars=6000/quotedSnippetMaxChars=200；评估阈值 lowRelevance=0.55/minTermCoverage=0.60/clarifyOnAmbiguousSubject=true/ambiguousSubjectCount=2。
  - EVAL_CONTROLLED_BASELINE（evaluator-config.json 冻结）：topK=5、threshold=0.0 显式传入每请求；warmup=3、rounds=3；检索超时 30s、端到端 60s；噪声阈值 precision<0.5。
- 环境探测（env-probe.json，两后端各一份）：MySQL 127.0.0.1:3306 可达=true；Redis 6379 可达=false（不影响 RAG 主链路，应用正常启动）；PG 192.168.200.153:5432 可达=true；应用 8080 启动成功（PG_VECTOR_PASSWORD 经用户环境变量注入会话，未落盘/日志/PATH）。模式：OFFLINE（AI_MODE=mock，无 ChatModel Bean；AI_API_KEY 存在但不启用）；检索后端：PGVECTOR 与 IN_MEMORY_LOCAL 均真实执行，BLOCKED 未现网诱导（见 C03）。
- 语料入库核实：PG 初始 0 向量行（reconcile dryRun pgTotalRows=0），MySQL 关联表含本迭代 3 文档旧 FIXED_SIZE 部分索引（2+2+1）与 D32 历史残留（d32eval-*，只读不动）。旧索引与冻结语料不一致 → 仅对本迭代 3 个 documentId 执行 DELETE+按语料重建（STRUCTURE/version=1，3+3+2=8 片段全 COMPLETED）。3 语料文档 knowledge_document.lifecycle_status=ACTIVE（JDBC 直查证据）。

## 数据集冻结
- 问题集 v1.0：20 问四场景各 5（ANSWERABLE/UNANSWERABLE/TERMINOLOGY/BOUNDARY_FUZZY），注入用例 ×2（QA-005 语料内嵌、QF-005 直接载荷）；annotatedAt=2026-11-20T18:59:23+08:00 盖章后冻结。
- manifest：13 文件 SHA-256；数据集哈希前缀 `75cc8d83cfb2`，语料 `aad6ac4df558`，config `72a1b84df35e`，UrlSafetyPolicy `e72b981b84e2`；每次评测启动复验通过。
- 绑定：local-hashing-embedding/local-v1/384/COSINE；vectorSpaceId 实测 `local|local-hashing-embedding|local-v1|384`。

## 评测执行
- 预热/正式轮次：3/3（每后端两次独立运行）；原始记录 60 条/运行（每条带 retrievalBackend）；退出码：两后端均 1（存在真实 FAIL）。
- 四态（用例级 C01-C18，合计=18）：PASS 13 / FAIL 3（C02、C06、C09）/ BLOCKED 1（C03）/ NOT RUN 1（C13）。
- 四态（题目级，每后端 20 问）：
  - IN_MEMORY_LOCAL：PASS 1 / FAIL 17 / BLOCKED 0 / NOT RUN 2。
  - PGVECTOR：PASS 0 / FAIL 20 / BLOCKED 0 / NOT RUN 0。
- Level：
  - L1 PASS：env-probe 与真实环境一致；双模式×三态判定逻辑经 SelfTest 七场景验证；两后端真实降级/直连均执行。
  - L2 PASS：20 问 schema 校验+负例拒绝；manifest 每次运行复验一致。
  - L3 PASS：SelfTest 75/75；指标全部由 raw-results.jsonl 复算；内存后端含稳定次级排序。
  - L4 部分达成：9 标签引擎自测全覆盖；基线真实用例覆盖 HIGH_NOISE/REFUSAL_ERROR/WRONG_CLARIFICATION 3 类（+PAGE_LOCATION_MISSING 统计与逐字探针的引用/元数据真实证据）；MISS_RECALL/FABRICATED_CITATION/TIMEOUT/MODEL_ERROR/RETRIEVAL_ERROR 在基线中无触发条件（recall=1.0、sources 仅 ANSWER 路径返回、无超时/后端故障），仅自测夹具覆盖，如实标 NOT RUN（基线内）。
  - L5 NOT RUN：前置条件 AI_MODE=deepseek+真实模型密钥+每问至多 1 次调用；本迭代零模型调用，离线天花板 L4，不伪称。

## 基线结果（按后端分列，永久只读：baseline/v1.0/<backend>/）

| 指标 | IN_MEMORY_LOCAL | PGVECTOR |
|---|---|---|
| hitRateAtK | 1.0000 | 1.0000 |
| recallMacro | 1.0000 | 1.0000 |
| precisionMacro | 0.2222 | 0.2222 |
| noiseMacro | 0.7778 | 0.7778 |
| refusalAccuracy | N/A（decisionsJudged=0） | 1.0000 |
| falseRefusalRate（误拒率） | N/A | 1.0000 |
| falseAnswerRate（误答率） | N/A | 0.0000 |
| clarifyAccuracy | N/A | 0.0000 |
| citationLegality | N/A | N/A（基线 withSources=0；逐字探针另证） |
| metadataCompleteness | N/A | N/A（同上） |
| 耗时字段 | inMemorySearchMs | queryEmbeddingMs/vectorSearchMs/retrievalTotalMs |

- 一致性 diff：两后端各自两次运行质量投影**逐字节一致**（consistency-diff-*.json，退出 0）；轮间一致性全部一致。
- 标签分布：IN_MEMORY：HIGH_NOISE:HIGH_NOISE_PRECISION×17。PGVECTOR：HIGH_NOISE:HIGH_NOISE_PRECISION×17、REFUSAL_ERROR:REFUSAL_FALSE_NEGATIVE×10、WRONG_CLARIFICATION:CLARIFY_EXPECTED_MISSING×2。
- 核心诚实发现：词法哈希 Embedding 相似度偏低（QA-001 Top1=0.3797<0.55），LOW_RELEVANCE 在 CLARIFY 之前判定 → 20 问全 REFUSE；可答题全部误拒、模糊题未澄清。检索排序本身正确（Top1 均为期望文档，跨后端 precision 完全一致）。**未调阈值美化**。
- 补充真实证据（基线外探针，不入基线）：逐字问题+documentId 过滤 → decision=ANSWER、generationStatus=NOT_CONFIGURED（受控态）、sources=3、sourceId=documentId、citationId=sourceRef=S1/S2、quotedSnippet 逐字支持、url=null、page=null、similarity=1.0（Embedding 确定性）。

## 真实失败与修复
| # | 根因 | 修复 | 验证 |
|---|---|---|---|
| 1 | Backends.java 3 处 double→Long 类型错误 | Math.round 包装 | 已执行通过（javac 零错误） |
| 2 | Jackson 2.19 JsonNode.fields() 弃用警告 ×2 | 改 properties() | 已执行通过（-Xlint:deprecation 零警告） |
| 3 | 离线无决策执行时决策类指标显示 0.0000 误导 | decisionsJudged=0 → N/A | 已执行通过（冻结基线显示 N/A） |
| 4 | node --test 传目录被当模块解析（MODULE_NOT_FOUND） | 改传测试文件路径 | 已执行通过（6/6 PASS） |
| 5 | PG 旧 FIXED_SIZE 部分索引与冻结语料不一致；自然键含 strategy，直接重索引会 COUNT_MISMATCH | 仅本迭代 3 documentId DELETE+重建 STRUCTURE | 已执行通过（8 片段 COMPLETED，reconcile 前后留痕） |
| 6 | CONFLICT 夹具无 MySQL 行被生命周期过滤短路 NO_EVIDENCE | 先 /documents/register 落 ACTIVE 行再索引 | 已执行通过（decision=CONFLICT/EVIDENCE_CONFLICT，conflictGroups=1） |
| 7 | C02 期望退出码 0 与真实 HIGH_NOISE FAIL 冲突 | 不修改运行器凑退出码；按 §2.3 退出码规则如实记 1 | 任务书内部矛盾，记录待 D38 澄清 |

## 逐条用例（C01-C18）
| 编号 | 状态 | 证据与说明 |
|---|---|---|
| C01 | PASS | PGVECTOR env-probe.json；OFFLINE 判定正确；检索/拒答/静态引用可测；生成 NOT_CONFIGURED 受控态 |
| C02 | FAIL | 降级机制正确（IN_MEMORY_LOCAL、inMemorySearchMs、指标带标记）；退出码 1≠期望 0，源于 17 题真实 HIGH_NOISE，按退出码规则优先，禁止隐藏失败凑 0 |
| C03 | BLOCKED | 现网诱导需使共享 PG/Embedding 不可用（破坏性，禁止）；判定逻辑经 SelfTest C17 七场景覆盖；未现网执行 |
| C04 | PASS | Dataset.java 校验+SelfTest 负例（非法 JSON/未冻结/非 20 问/非法 scenario 全拒绝） |
| C05 | PASS | 每次运行 manifest SHA-256 复验一致；篡改拦截路径 verifyManifest 抛异常（代码核实；未对冻结件做破坏性篡改演示） |
| C06 | FAIL | 检索命中 PASS（hitRate=1.0，QA-002 多证据覆盖）；决策期望 ANSWER 实际 REFUSE（LOW_RELEVANCE）→ 误拒，词法模型诚实发现；逐字探针证明 ANSWER 路径本身可用 |
| C07 | PASS | 8 题受控拒答，errorCode=LOW_RELEVANCE∈REFUSE_CODES；refusalAccuracy=1.0；零模型调用 |
| C08 | PASS | QT×5 期望术语全部出现在真实返回片段（missingTerms 空，无 MISS_RECALL）；决策误拒计入 C06 |
| C09 | FAIL | QF-003/004/005 REFUSE 符合标注；QF-001/002 期望 CLARIFY 实际 REFUSE（LOW_RELEVANCE 先于 CLARIFY）→ WRONG_CLARIFICATION；CONFLICT 夹具专项 PASS（基线外） |
| C10 | PASS | 两注入载荷均按普通文本检索展示，无执行迹象（injectionExecutionSuspected 未触发）；离线无生成，执行面天然关闭，完整判别力需 FULL |
| C11 | PASS | SelfTest 危险协议/盘符/环回/内网夹具全拦截；node --test 6/6；静态扫描无危险 sink；url 恒 null 无虚构 |
| C12 | PASS | 静态契约对照完成（C12-contract-comparison.md）；发现 SSE CitationDto 缺 paragraphIndex 死分支（低危展示级，记录不修）；动态帧抓取 NOT RUN（可选） |
| C13 | NOT RUN | 删除竞态全路径需 generation SUCCESS（mock 不可达）；生命周期拒绝子路径已验证 PASS（夹具 rejected=1→NO_EVIDENCE，fail-closed） |
| C14 | PASS | SelfTest 可控超时夹具按阶段标记 TIMEOUT_RETRIEVAL/GENERATION/TOTAL 且不中断；现网未诱导真实超时 |
| C15 | PASS | metrics-selftest.txt 75/75，先于每次正式评测执行 |
| C16 | PASS | SelfTest 同分次级排序断言；跨进程两次运行质量投影逐字节一致 |
| C17 | PASS | SelfTest 七场景（无/有密钥×无/有 PG+Embedding 失败+DB 失败+模型超时）判定全正确 |
| C18 | PASS | 两后端各自 diff 逐字节一致；基线分目录 IN_MEMORY_LOCAL/PGVECTOR 不混算 |

## 成本与 Token
- DeepSeek 调用 0 次、Token 0、零外网（离线+内存后端全程）。阈值模板 300/400/500 万仅对 FULL 模式生效，本迭代未触及。字符预算（CHARACTER）不伪称 Token。

## 遗留与边界
1. L5 NOT RUN 前置：AI_MODE=deepseek+密钥+真实模型；每问至多 1 次调用、预热不调模型。
2. C03 现网 BLOCKED 诱导、C13 删除竞态全路径、C14 现网真实超时：均需可控故障注入环境，本迭代以自测夹具覆盖逻辑。
3. 跨后端不可直接比较：耗时字段语义不同（inMemorySearchMs≠vectorSearchMs）；质量投影本迭代恰好一致（排序一致）但不得推广为"两后端等价"。
4. PG 侧 page/section 元数据丢失：直接入库 API（VectorIndexRequest.ChunkPayload）无 page/section 承载字段，PG 基线 page=null → PAGE_LOCATION_MISSING 仅统计；内存后端直读语料含 page。不改公共 DTO 签名，记录为 D38 候选。
5. MySQL 关联表 D32 历史残留（d32eval-* 关联行无 PG 向量）：只读保留，reconcile dryRun 留痕；不影响检索（检索以 PG 为准）。
6. 夹具清理完成：D37-EVAL-CONFLICT-901（向量+MySQL 登记行）与 D37-EVAL-LIFECYCLE-902（向量）均已删除，FIXTURE_REMAINING=0。
7. 跨机器复现：质量类字段（有序逻辑 chunkId/docId/relevant/决策/状态码/标签/status）设计为逐字节一致；耗时/时间戳/相似度浮点排除在投影外。本迭代在同机两次运行验证一致；跨机器验证待 D38。
8. D38 对接：以 baseline/v1.0/<backend>/ 为对照基线；更换神经 Embedding 或调阈值须升数据集/配置版本并重新冻结；C02 退出码期望与 §2.3 规则矛盾需任务书层面澄清。

## 官方文档溯源
- Spring AI Reference - RAG | Spring AI 参考-RAG：https://docs.spring.io/spring-ai/reference/api/rag.html
- Spring AI Reference - Vector Databases | Spring AI 参考-向量数据库：https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Spring AI Reference - PGvector Vector Store | Spring AI 参考-PGvector 向量存储：https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- Spring AI Reference - Embedding Model API | Spring AI 参考-Embedding 模型 API：https://docs.spring.io/spring-ai/reference/api/embeddings.html
- pgvector - Open-source vector similarity search for Postgres | pgvector-PostgreSQL 开源向量相似度搜索：https://github.com/pgvector/pgvector
