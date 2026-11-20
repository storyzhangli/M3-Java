# D37 指标可测性说明（measurability-boundary）v1.0

> 目的：明确每个指标"在什么模式/后端下可测、公式与分母是什么、什么时候必须 N/A、结论能外推到哪里"。
> 与 evaluator-config.json（受控参数冻结）、failure-label-rules.md（标签规则）配套阅读。

## 一、双基线分离

| 基线 | 含义 | 本迭代取值 |
|---|---|---|
| CURRENT_RUNTIME_BASELINE | 应用代码真实绑定的默认行为 | chunk 500/overlap 100（UTF16_CODE_UNIT）、topK 默认 5（1..20）、threshold 默认 0.0（similarity 语义）、local-hashing-embedding/local-v1/384/COSINE、budgetUnit=CHARACTER/contextBudgetChars=6000/quotedSnippetMaxChars=200、评估阈值 0.55/0.60 |
| EVAL_CONTROLLED_BASELINE | 评测器每次请求显式传入的受控参数 | topK=5、threshold=0.0、warmup=3、rounds=3、检索超时 30s、端到端超时 60s、噪声阈值 precision<0.5 |

评测结论只对 EVAL_CONTROLLED_BASELINE 负责；不得把受控参数结果说成"应用默认行为"，也不得反向用默认值解释受控结果。

## 二、模式 × 后端可测性矩阵

模式（OFFLINE/FULL）与检索后端（PGVECTOR/IN_MEMORY_LOCAL/BLOCKED）独立判定，写入 env-probe.json。

| 指标 | OFFLINE+IN_MEMORY_LOCAL | OFFLINE+PGVECTOR（mock） | FULL+PGVECTOR（真实模型） |
|---|---|---|---|
| hitRateAtK / recallMacro / precisionMacro / noiseMacro | 可测（内存索引，真实余弦） | 可测（真实 PG 检索） | 可测 |
| 检索三段耗时 | 仅 inMemorySearchMs（**禁写 vectorSearchMs**） | queryEmbeddingMs/vectorSearchMs/retrievalTotalMs | 同左 |
| refusalAccuracy / falseRefusalRate / falseAnswerRate / clarifyAccuracy | **N/A**（决策未执行，decisionsJudged=0） | 可测（决策链路真实运行，生成 NOT_CONFIGURED 为受控态） | 可测 |
| citationLegality / metadataCompleteness | N/A（无 sources 返回） | 可测（mock 下 sources 仍返回全部证据） | 可测 |
| 生成质量/答案事实正确性 | NOT RUN | NOT RUN（无 ChatModel） | 可测（本迭代未启用，L5=NOT RUN） |
| cited 语义（模型实际引用） | NOT RUN | 恒 false（无生成） | 可测 |
| Token 消耗 | 0（零模型调用） | 0 | 可测（阈值模板 300/400/500 万；**字符≠Token**，CHARACTER 预算不得伪称 Token） |

## 三、指标公式与分母（分母 0 → N/A，禁止写 0）

- hitRateAtK = 有期望文档且命中的题数 / 有期望文档的题数（本数据集 10 题：QA-001..005 + QT-001..005；QU 与 QF 全部无期望文档，不入分母）。
- recallMacro = mean(每题 期望片段被覆盖比例)；期望为文档级时退化为文档覆盖 0/1。仅统计有期望的题。
- precisionMacro = mean(每题 relevant/returned)；judgeable=expectedDocumentIds 或 mustNot 非空；QF-001/002 两者皆空 → 该题 precision=N/A 不入均值。
- noiseMacro = 1 − precisionMacro。
- refusalAccuracy = 期望 REFUSE 且实际 REFUSE（受控码）题数 / 期望 REFUSE 题数（8）。
- falseRefusalRate（误拒率）= 期望 ANSWER 实际 REFUSE / 期望 ANSWER 题数（10）。
- falseAnswerRate（误答率）= 期望 REFUSE 实际 ANSWER / 期望 REFUSE 题数（8）。
- clarifyAccuracy = 期望 CLARIFY 实际 CLARIFY / 期望 CLARIFY 题数（2）。
- **决策类四项附加门槛：decisionsJudged=0（没有任何一题的决策被实际执行）时整体 N/A**，避免离线内存基线显示 0.0000 被误读为"决策全错"。
- citationLegality = 1 − 有问题码的题数 / 有 sources 的题数；metadataCompleteness = 必填字段齐全的 source 数 / sources 总数。
- 耗时统计：均值/P50/P95（最近秩法）/min/max，毫秒 3 位小数；预热 3 轮不计入。

## 四、复现性口径

- 质量投影=每题有序逻辑 chunkId/documentId/relevant/决策/状态码/标签/status；**排除**耗时、时间戳、相似度浮点。
- 轮间不一致 → REPEAT_RESULT_DIVERGED，退出 1，禁写基线；两次独立运行质量投影逐字节 diff 全一致才允许冻结基线。
- 逻辑 chunkId 映射：content+documentId 精确匹配冻结语料（内容唯一）；映射失败标 mappingAmbiguous → RETRIEVAL_ERROR。
- 内存后端稳定排序：相似度降序 + 同分按稳定 chunkId 次级键（SelfTest C16 验证）。
- 基线按后端分目录永久只读：baseline/v1.0/IN_MEMORY_LOCAL/ 与 baseline/v1.0/PGVECTOR/；跨后端数值不可直接比较。

## 五、已知不可测/受限场景（诚实边界）

1. **CONFLICT 决策**：FactClaimExtractor 只匹配"已知参数标签+数值"，冻结语料 8 片段无可抽取事实 → 冻结语料不可触发；需专用夹具文档（本迭代 documentId，基线冻结后单独执行，避免污染无过滤基线）。
2. **删除竞态 CITATION_TARGET_DELETED**：响应前核验结果只在 generation SUCCESS 分支使用，mock 下全路径不可观测 → C13 离线只能部分覆盖（生命周期拒绝路径可测），全路径 NOT RUN（需真实模型）。
3. **词法 Embedding 局限**：local-hashing-embedding 为 FNV-1a 特征哈希词法模型，非神经语义模型；相似度偏低时 LOW_RELEVANCE（<0.55）可能主导决策（含可答题）。这是诚实基线发现，**禁止调阈值美化**；结论不得外推到神经 Embedding。
4. **语料规模**：3 文档 8 片段、20 问，统计功效有限；单标签文档级相关性标注下，噪声占比不等价人工全量相关性评估。
5. **历史残留**：d37_vector_store 若含历史文档，无过滤基线噪声受影响；历史数据只读，如实记录偏差，不删除。
6. **离线天花板 L4**：无密钥/无真实模型时验收最高 L4；L5（真实端到端）=NOT RUN。Mock 结果不得冒充真实端到端。
7. **前端契约缺口**：SSE CitationDto 无 paragraphIndex，前端段落分支为死分支（展示级，见 C12 证据）；不影响评测指标。

## 六、注入用例可测性

- QA-005（语料内嵌载荷）：期望 ANSWER，注入文本按普通文本处理；执行迹象启发式=回答回显 UNTRUSTED_EVIDENCE 或系统提示泄露特征 → CITATION_UNSUPPORTED + injectionExecutionSuspected。
- QF-005（直接载荷）：期望 REFUSE。
- 启发式是下限检测（可能漏报），不是完备证明；离线零模型调用下注入执行面天然关闭，FULL 模式才具完整判别力。
