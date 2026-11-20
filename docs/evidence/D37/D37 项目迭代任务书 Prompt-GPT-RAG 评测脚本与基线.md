# D37 项目迭代任务书 Prompt：RAG 评测脚本与基线

> 使用方式：本文件作为独立任务书附件，投喂给 Codex / 豆包等代码 Agent。
>
> 执行原则：**先扫描本地真实工程 → 确认评测接口与运行依赖 → 设计数据集和指标 → 实现评测器 → 真实运行 → 保存不可变基线与证据。**
>
> 核心目标：开发可重复执行的轻量 RAG 评测脚本，建立至少 20 条固定问题组成的第一版基线，明确每项指标的可测性边界。

---

## 一、项目迭代目标

**项目迭代名称：Day 37：RAG 评测脚本与基线**

本次迭代不重新开发 RAG 主链路，而是在现有 D37 工程基础上增加独立、可重复执行的评测能力。

必须完成：

1. 固定至少 20 条问题，建议 24 条，覆盖四类场景。
2. 建立不可变的问题集、文档集、Embedding 版本标识。
3. 实现自动识别环境的离线模式和全量模式。
4. 输出逐题评测 CSV、结构化汇总和 Markdown 报告。
5. 定义失败标签及可复算规则。
6. 建立第一版基线，保留原始结果和环境信息。
7. 明确无网络、无密钥、无向量库时的指标可测性边界。

**本次不做：**

- 不修改 RAG 核心业务决策规则以追求更高分数。
- 不为了让评测通过而修改问题集 Gold 标注。
- 不引入复杂评测平台或重量级第三方框架。
- 不把本地模拟检索结果伪装成真实 pgvector 检索结果。
- 不把模型自评当作引用正确性或拒答质量的权威真值。
- 不覆盖 D36 已冻结的实验数据和历史基线。

## 二、项目路径与技术基线

### 2.1 本地路径

| 项目         | 路径                                                       |
| ------------ | ---------------------------------------------------------- |
| 项目根目录   | `E:\AI\project\AI-Learning-one\M3-Java\D37`                |
| 模块目录     | `E:\AI\project\AI-Learning-one\M3-Java\D37\D37_RAG_ESAB`   |
| 技术文档目录 | `E:\AI\project\AI-Learning-one\M3-Java\docs\proj_docs`     |
| 证据目录     | `E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D37`  |
| 临时文件目录 | `E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs\D37` |
| 日志目录     | `E:\AI\project\AI-Learning-one\M3-Java\logs\D37`           |

临时目录只允许新增和使用文件，**严禁自动删除、清空或覆盖用户已有文件**。

### 2.2 技术版本

以本地实际 POM 和配置扫描结果为准，当前仓库已确认：

- JDK 21
- Spring Boot 3.5.16
- Spring AI 1.1.8
- Maven 父工程：`D37`
- Maven 子模块：`D37_RAG_ESAB`
- Embedding：`local-hashing-embedding`
- Embedding Version：`local-v1`
- 向量维度：384
- 距离度量：COSINE
- 向量库：PostgreSQL + pgvector

禁止升级技术版本或修改与本次评测无关的依赖。

### 2.3 D36 冻结资产

D37 应优先复用以下 D36 资产：

| 资产              | 版本 / 内容               |
| ----------------- | ------------------------- |
| 文档集            | `D36-CORPUS-v1`           |
| 文档数量          | 3                         |
| 原始证据片段      | 8                         |
| 问题集            | `D36-DATASET-v1`，10 题   |
| Embedding         | `local-hashing-embedding` |
| Embedding Version | `local-v1`                |
| 维度              | 384                       |
| 距离度量          | COSINE                    |

D36 文档 ID：

- `DOC-CNC-COOLING-001`
- `DOC-SERVO-TROUBLE-002`
- `DOC-WORKORDER-SPEC-003`

D36 原始问题集保留为历史兼容回归资产，不允许修改或覆盖。

D37 新增独立问题集 `D37-DATASET-v1.0`，建议 24 题，四类各 6 题。新问题必须根据真实语料逐条标注 Gold Evidence，不能只填写文档 ID 而不核验具体证据片段。

### 2.4 当前工程能力

已存在、优先复用的核心代码：

- `RagRetrievalService`
- `SimilaritySearchService`
- `RagOrchestrator`
- `ContextAssemblyService`
- `EvidenceAssessmentService`
- `GenerationService`
- `ResponseValidationService`
- `CitationVerificationService`

`SimilaritySearchService` 已提供：

- Query Embedding 耗时
- Vector Search 耗时
- Retrieval Total 耗时

`RagOrchestrator` 已提供检索、上下文组装、生成和总耗时统计。

实施前必须核对这些字段在当前本地工程中的真实实现、DTO 序列化结果和可调用入口。不要重复实现已有功能。

---

## 三、第一阶段：执行前扫描

### 3.1 扫描范围

先扫描以下内容：

1. 父 POM、子模块 POM、Maven 插件及测试依赖。
2. `application.yml`、`application-test.yml`、本地环境配置。
3. AI 模型模式、API Key 配置和 Embedding 配置。
4. pgvector 数据源、连接池、VectorStore 初始化和健康检查。
5. `SimilaritySearchService`、`RagRetrievalService`、`RagOrchestrator`。
6. `RagAnswerResponse`、`RagTimingView`、`SimilaritySearchResultView`。
7. `EvidenceAssessmentService` 和 `ResponseValidationService`。
8. D36 的语料清单、问题集、实验结论和哈希记录。
9. 当前 Java 测试、测试资源及测试运行方式。

扫描结果必须记录：

- Git HEAD Commit。
- 工作区是否干净。
- 实际配置值及配置来源。
- 真实检索调用入口。
- 真实生成调用入口。
- 数据库连接检测入口。
- 已存在的耗时字段。
- 已存在的引用和拒答判断字段。
- 可以复用的测试工具。

### 3.2 关键设计判断

必须先确认：

- 无 AI_API_KEY 时，应用是否可以正常启动。
- 无 pgvector 时，Spring Boot 是否仍然能够启动。
- pgvector 不可用时，能否通过独立的本地 Embedding + 内存精确余弦检索执行离线评测。
- 当前本地 Embedding 是否可以在不访问外网的情况下完成向量化。
- 现有 RAG 接口能否返回评测所需的检索证据和引用核验信息。

如果应用启动阶段强依赖数据库，优先设计独立评测启动入口或评测配置，不要为了离线评测随意改变生产配置。

### 3.3 扫描报告

输出：

`docs/evidence/D37/00-baseline-scan.md`

报告必须区分：

- 已通过源码确认。
- 已通过本地运行确认。
- 仅通过配置推断。
- 尚未验证。

禁止把配置文件中的连接地址当作数据库已成功连接的证据。

---

## 四、评测模式设计

采用两种对外评测模式，但内部应区分向量库可用性，避免把不同检索实现混为一谈。

### 4.1 模式定义

| 模式     | 触发条件                            | 可测指标                                                 |
| -------- | ----------------------------------- | -------------------------------------------------------- |
| 离线模式 | 无 AI_API_KEY，或真实向量库不可用   | Recall@K、HitRate@K、Precision@K、检索耗时、元数据完整性 |
| 全量模式 | AI_API_KEY 有效且真实 pgvector 可用 | 离线模式全部指标 + 引用正确性、拒答/澄清质量、端到端延迟 |

离线模式内部记录实际检索后端：

- `PGVECTOR`：真实 pgvector 可用，仍可执行检索评测。
- `IN_MEMORY_LOCAL`：无向量库，使用本地 Embedding 和内存检索适配器。
- `BLOCKED`：本地 Embedding 或必要语料无法初始化，不能完成检索评测。

### 4.2 自动识别规则

启动后执行轻量健康检测：

1. 检查 `AI_API_KEY` 是否存在且非空。
2. 检查 pgvector 是否能够完成真实连接及必要查询。
3. 检查本地 Embedding 是否可用。
4. 根据检测结果选择评测模式和检索后端。
5. 将检测结果、模式、后端和降级原因写入报告。

禁止仅根据配置文件中的 `enabled=true` 判断向量库可用。

**重要：AI_API_KEY 缺失不意味着检索能力缺失。** 当前工程的检索链路使用本地 Embedding，不应要求检索评测必须调用 DeepSeek。

### 4.3 离线检索实现要求

当 pgvector 不可用时：

- 使用当前配置对应的本地 Embedding 模型。
- 使用冻结语料构建内存索引。
- 余弦相似度必须按实际向量计算。
- Top-K 排序规则固定。
- 相同分数必须使用稳定的 Chunk ID 作为次级排序键。
- 元数据字段必须来自真实冻结语料。
- 不调用外部模型、不访问外网。
- 不伪造 pgvector 的查询耗时或数据库性能。

内存检索结果必须标记 `IN_MEMORY_LOCAL`，不能与 `PGVECTOR` 结果混为同一检索后端。

离线模式的结果可以用于建立本地算法基线，但与 D36 的 pgvector 结果比较时，必须注明检索后端差异。

### 4.4 全量模式

全量模式要求：

- 真实 pgvector 连接成功。
- AI_API_KEY 存在并且真实模型调用成功。
- 能够完整执行 RAG 检索、证据评估、生成、引用校验流程。

如果 API Key 无效、上游超时或模型服务异常：

- 保留已完成的检索结果。
- 对模型相关指标标记 `BLOCKED` 或 `ERROR`。
- 不得把模型调用失败统计为拒答正确。
- 不得静默降级为 Mock 后仍报告全量评测成功。

---

## 五、固定问题集设计

### 5.1 数据集版本

新增：

`D37-DATASET-v1.0`

建议 24 条问题，四类各 6 条：

| 类别                     | 数量 | 评测目标                     |
| ------------------------ | ---: | ---------------------------- |
| 可回答问题               |    6 | 检查检索召回及回答证据       |
| 无答案问题               |    6 | 检查无证据时是否正确拒答     |
| 故障码 / 型号 / 专业术语 |    6 | 检查专业词汇和术语检索       |
| 边界模糊问题             |    6 | 检查澄清、拒答和证据不足处理 |

允许根据语料实际内容调整题目，但不能为了增加问题数量而编造语料中不存在的事实。

### 5.2 每条问题必须包含

- `questionId`
- `question`
- `category`
- `expectedBehavior`
- `expectedAnswerable`
- `relevantDocumentIds`
- `goldEvidence`
- `expectedDecision`
- `notes`

其中：

`expectedBehavior`：

- `ANSWER`
- `REFUSE`
- `CLARIFY`

`expectedDecision` 必须与现有证据评估决策枚举建立明确映射。

`goldEvidence` 至少包含：

- `documentId`
- `sourceChunkId`
- `excerpt`

可回答问题必须标注一个或多个 Gold Evidence。

无答案问题的 Gold Evidence 为空，并记录无答案原因。

边界模糊问题应明确预期是补充信息、澄清还是拒答，不能全部简单归为无答案。

### 5.3 标注规则

- Gold Evidence 必须来源于冻结文档集。
- 文档 ID 必须真实存在。
- Gold Evidence 摘录必须能够在原始文档中找到。
- 不允许根据系统检索结果反向修改 Gold 标注。
- 相关性判断采用固定文本归一化和覆盖率规则。
- 规则版本写入数据集元数据。
- Gold 标注由人工核对，保存标注说明。

建议沿用 D36 的证据覆盖率思路，但需要重新检查并明确：

1. 文本归一化规则。
2. Gold Span 覆盖率计算规则。
3. 一个 Gold Evidence 被多个 Chunk 覆盖时如何计数。
4. 一个 Chunk 覆盖多个 Gold Evidence 时如何计数。
5. 文档级命中和 Chunk 级命中的区别。

不能把文档级 HitRate 和 Chunk 级 Recall 混为同一个指标。

### 5.4 数据集冻结

输出：

- `docs/evidence/D37/dataset/D37-DATASET-v1.0.json`
- `docs/evidence/D37/dataset/dataset-manifest.json`

Manifest 至少记录：

- 数据集版本。
- 问题数量。
- 四类问题数量。
- 数据集 SHA-256。
- 文档集版本。
- 文档集 SHA-256。
- Embedding 模型名称。
- Embedding 版本。
- Embedding 维度。
- 距离度量。
- Gold 标注规则版本。
- 创建时间。
- Git Commit。

数据集一旦形成正式基线，不得静默修改。后续修改必须升级版本号。

---

## 六、文档集与 Embedding 版本冻结

### 6.1 文档集

沿用：

`D36-CORPUS-v1`

必须检查 D37 实际语料文件与 D36 Manifest 是否一致。

不能只比较文件名，必须计算 SHA-256。

如果内容一致，沿用原版本；如果内容发生变化，必须升级文档集版本，不得继续声称是 D36-CORPUS-v1。

### 6.2 Embedding

冻结以下信息：

| 属性            | 基线值                  |
| --------------- | ----------------------- |
| Provider        | local                   |
| Model           | local-hashing-embedding |
| Version         | local-v1                |
| Dimensions      | 384                     |
| Distance Metric | COSINE                  |

同时记录：

- Embedding 实现类。
- 代码版本或 Git Commit。
- 配置值。
- 向量维度检查结果。
- 是否使用外部服务。

未来替换 Embedding 模型时，必须创建新基线，不允许直接与旧版本混算。

---

## 七、评测指标与计算口径

### 7.1 检索指标

| 指标                  | 定义                                                        | 适用范围                    |
| --------------------- | ----------------------------------------------------------- | --------------------------- |
| Recall@K              | Top-K 中命中的 Gold Evidence 数 / 该题全部 Gold Evidence 数 | 可回答问题                  |
| HitRate@K             | Top-K 至少命中一个 Gold Evidence 的问题数 / 可回答问题数    | 可回答问题                  |
| Precision@K           | Top-K 中相关结果数 / 实际返回结果数                         | 所有有 Gold Evidence 的问题 |
| Empty Retrieval Rate  | 返回结果为空的问题数 / 检索问题总数                         | 全部问题                    |
| NoiseRatio            | 非相关返回 Chunk 数 / 返回 Chunk 总数                       | 有检索结果的问题            |
| Metadata Completeness | 必需元数据完整的结果数 / 返回结果总数                       | 全部检索结果                |
| Retrieval Latency     | 检索阶段耗时                                                | 全部检索问题                |

注意：

- Recall@K 和 HitRate@K 必须分开统计。
- 无答案问题不参与可回答问题的 Recall@K 分母。
- 空检索时 Precision@K 不应直接伪造为 0 或 1，应标记无定义，并单独统计 Empty Retrieval。
- 无答案问题应单独统计误检率，即不应回答的问题中出现有效检索证据的比例。
- 所有指标必须能由原始逐题结果复算。

### 7.2 全量模式指标

| 指标                     | 定义                           | 评测方式                 |
| ------------------------ | ------------------------------ | ------------------------ |
| Citation Validity        | 引用 ID 是否真实存在于本轮证据 | 程序校验                 |
| Citation Correctness     | 引用是否支持对应事实断言       | 程序规则 + Gold 标注核验 |
| Fabricated Citation Rate | 虚构或无效引用数 / 总引用数    | 程序校验                 |
| Refusal Accuracy         | 无答案问题是否正确拒答         | 预期行为与实际决策对比   |
| Clarification Accuracy   | 模糊问题是否按预期澄清         | 预期行为与实际决策对比   |
| False Refusal Rate       | 可回答问题被错误拒答的比例     | Gold 标注对比            |
| False Answer Rate        | 无答案问题被错误回答的比例     | Gold 标注对比            |
| End-to-End Latency       | 从请求开始到完整响应结束的耗时 | 客户端计时               |

禁止仅凭回答中包含 `[S1]` 等引用标记就判断引用正确。

引用正确性必须检查引用对应的真实证据是否支持相关断言。

拒答质量不能简单按“返回空答案”判断。需要区分：

- 正确拒答。
- 错误拒答。
- 应澄清但直接回答。
- 应回答但错误拒答。
- 应拒答但生成了无证据答案。

### 7.3 延迟统计

必须记录：

- Query Embedding 耗时。
- Vector Search 耗时。
- Retrieval Total 耗时。
- Context Assembly 耗时（可取得时）。
- Generation 耗时（全量模式）。
- End-to-End 耗时。

汇总：

- Average
- P50
- P95
- 最大值
- 超时数量

计时使用单调时钟，避免系统时间调整影响耗时测量。

---

## 八、失败标签设计

每题允许同时出现多个失败标签。

| 标签                  | 触发规则                             |
| --------------------- | ------------------------------------ |
| `MISS_RECALL`         | 可回答问题没有召回全部 Gold Evidence |
| `HIGH_NOISE`          | 噪声占比超过预先冻结的阈值           |
| `FABRICATED_CITATION` | 出现不存在或无证据支持的引用         |
| `WRONG_REFUSAL`       | 预期回答却拒答，或预期拒答却回答     |
| `WRONG_CLARIFICATION` | 预期澄清但未澄清，或不应澄清却澄清   |
| `TIMEOUT`             | 请求超过冻结的超时阈值               |
| `EMPTY_RETRIEVAL`     | 检索结果为空                         |
| `METADATA_INCOMPLETE` | 必需元数据缺失                       |
| `MODEL_ERROR`         | 模型调用失败                         |
| `RETRIEVAL_ERROR`     | 检索执行异常                         |

失败标签规则必须：

1. 在代码中集中定义。
2. 在技术文档中给出明确公式和例子。
3. 通过单元测试验证。
4. 输出每题的完整标签数组。
5. 不能依靠人工主观判断自动打标签。

噪声阈值、超时阈值必须在正式运行前冻结，并写入配置快照。不能看到结果后再调整阈值以美化报告。

---

## 九、评测脚本设计

### 9.1 输入

固定 JSONL 或 JSON 数据集。

建议使用 JSON 数据集保存版本、问题和 Gold 标注，评测器内部逐条执行；如果使用 JSONL，则必须另存 Manifest。

输入包括：

- 问题集。
- 文档集版本。
- Embedding 版本。
- Top-K。
- 检索过滤配置。
- 超时配置。
- 噪声阈值。
- 评测模式（支持 AUTO）。

### 9.2 输出

至少输出：

1. `baseline.csv`
2. `baseline-summary.json`
3. `baseline-report.md`
4. `evaluation-raw-results.jsonl`
5. `environment-snapshot.json`
6. `failure-cases.csv`

逐题 CSV 必须包含：

- runId
- questionId
- question
- category
- expectedBehavior
- actualBehavior
- retrievedCount
- relevantCount
- matchedRelevantCount
- recallAtK
- hitRateAtK
- precisionAtK
- noiseRatio
- metadataComplete
- retrievalBackend
- retrievalLatencyMs
- endToEndLatencyMs
- failureLabels
- executionStatus

无法测量的指标必须使用空值或明确状态，不允许填 0 冒充真实结果。

### 9.3 一条命令运行

提供 Windows PowerShell 入口：

`docs/evidence/D37/scripts/run-rag-evaluation.ps1`

建议实现：

- 自动检查 JDK、Maven。
- 自动检查配置和环境变量。
- 自动识别评测模式。
- 执行固定数据集。
- 保存日志和原始结果。
- 输出最终报告路径。
- 返回正确退出码。

最终必须实现一条命令即可运行：

`.\docs\evidence\D37\scripts\run-rag-evaluation.ps1`

如脚本位置或 Maven 启动方式需要调整，以本地实际工程为准，但必须在报告中给出唯一推荐命令。

脚本不得自动清理临时目录或历史基线文件。

---

## 十、确定性与可复现性要求

这是本次迭代最重要的工程要求。

### 10.1 必须确定性一致的内容

在相同代码、数据、模型、参数和检索后端条件下：

- 问题集内容一致。
- Gold 标注一致。
- 文档集内容一致。
- Embedding 版本一致。
- 检索参数一致。
- Top-K 结果排序规则一致。
- 相关性判断规则一致。
- Recall@K 计算一致。
- HitRate@K 计算一致。
- Precision@K 计算一致。
- 失败标签一致。
- 汇总统计公式一致。

如果同一环境重复执行，确定性指标应保持一致。

### 10.2 不能承诺跨机器完全一致的内容

以下指标受运行环境影响：

- Query Embedding 耗时。
- Vector Search 耗时。
- End-to-End Latency。
- P50 / P95 延迟。

数据库版本、硬件、CPU 负载、网络、缓存和并发状态都会影响耗时。

因此：

- 不允许把耗时作为跨机器完全一致的验收条件。
- 必须记录运行环境快照。
- 同一机器同一配置下可以进行重复性比较。
- 跨机器比较应关注分布、统计值和环境差异，而不是要求每个毫秒完全相同。

### 10.3 全量模式的额外不确定性

远程模型生成可能受到模型服务版本、服务端采样、提示词、网络和服务状态影响。

必须记录：

- 模型名称和版本。
- 请求参数。
- Prompt 版本。
- 实际响应状态。
- 调用时间。
- 超时和异常。
- 可获取的 Token Usage。

全量模式结果不应宣称跨时间、跨机器逐字节一致。

### 10.4 永久保存基线

正式基线采用版本化目录：

`docs/evidence/D37/baseline/v1.0/`

保存：

- 问题集及 SHA-256。
- 文档集 Manifest。
- Embedding 配置快照。
- 检索参数快照。
- 环境快照。
- 原始 JSONL。
- baseline.csv。
- 汇总 JSON。
- Markdown 报告。

正式基线形成后禁止覆盖。

后续重新运行应生成新的 `runId` 和独立运行目录。只有在代码、数据和参数符合冻结条件时，才允许进行可比性分析。

---

## 十一、测试要求

至少完成以下测试：

### 11.1 数据集测试

- 问题数量不少于 20。
- 四类问题数量符合设计。
- 问题 ID 不重复。
- Gold Evidence 对应真实文档。
- 无答案问题 Gold Evidence 为空。
- 数据集 SHA-256 可重复计算。

### 11.2 指标测试

使用人工构造的小型测试数据验证：

- Recall@K。
- HitRate@K。
- Precision@K。
- Empty Retrieval。
- NoiseRatio。
- Metadata Completeness。
- 无答案误检率。
- P50 / P95 计算。

每项必须覆盖正常、边界和空值情况。

### 11.3 模式识别测试

至少验证：

1. 无 API Key、无 pgvector。
2. 有 API Key、无 pgvector。
3. 无 API Key、有 pgvector。
4. 有 API Key、有 pgvector。
5. 本地 Embedding 初始化失败。
6. 数据库连接失败。
7. 模型调用超时。

模式识别必须根据真实健康检测结果，而不是只检查配置字符串。

### 11.4 失败标签测试

每种失败标签至少一个正向测试和一个不应触发的反向测试。

### 11.5 回归测试

不得破坏现有：

- RAG 检索接口。
- RAG 问答接口。
- 引用核验逻辑。
- 拒答和澄清决策。
- 现有 Mock 模式。
- 现有配置兼容性。

运行必要 Maven 测试，并保留完整测试结果。

---

## 十二、今日交付物

### 12.1 工程代码

- 可执行评测入口。
- 自动模式识别。
- 离线检索评测能力。
- 全量 RAG 评测能力。
- 指标计算器。
- 失败标签分类器。
- CSV / JSON / Markdown 报告生成器。
- 单元测试和集成测试。

### 12.2 数据资产

- `D37-DATASET-v1.0.json`
- `dataset-manifest.json`
- `baseline.csv`
- `baseline-summary.json`
- `evaluation-raw-results.jsonl`
- `environment-snapshot.json`
- `failure-cases.csv`

### 12.3 技术文档

文档一：

`D37-项目迭代实施记录-RAG评测脚本与基线.md`

必须包含：

- 项目目标。
- 原始工程扫描。
- 设计方案。
- 代码实现。
- 运行命令。
- 测试结果。
- 数据集说明。
- 基线报告。
- 指标解释。
- 失败案例。
- Git Commit。
- 已知问题。

文档二：

`D37-技术原理与实践指南-RAG评测指标与可复现基线.md`

必须包含：

- RAG 评测整体流程。
- 检索指标的定义和计算。
- Recall@K 与 HitRate@K 的区别。
- 引用正确性评估方法。
- 拒答质量评估方法。
- 离线和全量模式的差异。
- 确定性与非确定性边界。
- 失败标签设计。
- 评测数据集标注方法。
- 如何运行和分析基线。
- 后续参数实验如何复用基线。

### 12.4 证据

统一保存在：

`docs/evidence/D37/`

至少包含：

- `00-baseline-scan.md`
- `dataset/`
- `baseline/v1.0/`
- `test-results/`
- `failure-cases.csv`
- `run-logs/`
- `README.md`

---

## 十三、最终交付报告格式

最终报告必须按以下结构输出：

### 1. 项目完成情况

- 完成内容。
- 未完成内容。
- 阻塞事项。

### 2. 工程实现

- 新增类。
- 修改类。
- 评测执行入口。
- 自动模式识别逻辑。

### 3. 数据集

- 数据集版本。
- 问题总数。
- 四类问题数量。
- 文档集版本。
- Embedding 版本。
- SHA-256。

### 4. 基线结果

- Recall@K。
- HitRate@K。
- Precision@K。
- Empty Retrieval Rate。
- NoiseRatio。
- Metadata Completeness。
- 引用正确性。
- 拒答准确率。
- 平均延迟、P50、P95。

未执行的指标必须标记 `NOT_RUN`，无法执行的指标必须标记 `BLOCKED` 并说明原因。

### 5. 失败案例

列出：

- 问题 ID。
- 问题内容。
- 预期结果。
- 实际结果。
- 失败标签。
- 原因分析。

### 6. 可测性边界

明确回答：

- 无网络能测什么？
- 无 API Key 能测什么？
- 无 pgvector 能测什么？
- 只有本地 Embedding 能测什么？
- 哪些指标必须依赖真实模型？
- 哪些结果不具备跨机器可比性？

### 7. 可复现性

- 固定资产版本。
- 参数快照。
- 环境快照。
- 重复执行结果。
- 确定性指标一致性。
- 延迟波动说明。

### 8. Git 交付

- GitHub 仓库链接。
- 本次 Commit。
- 关键文件链接。
- 测试执行结果。

---

## 十四、Token 与工程执行约束

1. 不要无目的读取整个仓库。
2. 先扫描 POM、配置、核心调用链和现有测试。
3. 使用脚本批量统计文件、配置和数据集。
4. 只读取与评测实现直接相关的源码。
5. 优先复用现有检索、耗时、引用校验能力。
6. 避免重复实现 RAG 主链路。
7. 测试日志保存到指定日志目录，终端只输出摘要。
8. 不要反复运行完整 24 题全量评测来调试单个指标，优先使用小型固定测试夹具。
9. 正式基线只在代码和数据集冻结后执行。
10. 不允许为减少 Token 而跳过必要测试、数据校验或证据留存。

---

## 十五、工程红线

- 不允许伪造评测结果。
- 不允许虚构模型调用成功。
- 不允许虚构数据库连接成功。
- 不允许把 Mock 结果标记为真实模型评测。
- 不允许把内存检索标记为 pgvector 检索。
- 不允许修改 Gold 标注来迎合结果。
- 不允许覆盖 D36 历史数据。
- 不允许覆盖已经冻结的 D37 基线。
- 不允许把耗时指标承诺为跨机器完全一致。
- 不允许把未执行的指标填成 0。
- 不允许将模型自评包装为客观真值。
- 不允许自动删除 `docs/temp_docs/D37` 中的文件。

**最终验收标准：至少 20 条固定问题、四类场景覆盖、离线与全量模式可识别、逐题结果可追溯、指标可复算、失败标签可验证、基线不可变、执行过程可重复。**

本次迭代的重点不是追求高分，而是建立一套可信、可复现、后续 D38 及之后的 RAG 优化都能持续复用的评测基线。