# D37 失败标签规则（failure-label-rules）v1.0

> 本文档与评测器 `FailureLabels.java` / `Evaluator.java` 实现逐条一致，全部判定可由 raw-results.jsonl 复算。
> 每题可多标签；同一标签下原因码至多取一个（按本文档顺序优先）；标签格式 `一阶标签:原因码`。
> 冻结纪律：规则随数据集 v1.0 一并冻结（manifest 含评测器源码哈希），看结果后改规则必须升版本。

## 一、9 个一阶标签与细分原因码

### 1. MISS_RECALL（漏召回）
触发前提：该题有期望文档（expectedDocumentIds 非空）或期望片段（expectedChunkIds 非空）。

| 原因码 | 触发条件 |
|---|---|
| MISS_RECALL_EMPTY | 有期望文档，检索返回 0 条（成功空结果，非失败） |
| MISS_RECALL_FILTERED | 有期望文档未覆盖，且本题带过滤条件（documentId/version/strategy/title） |
| MISS_RECALL_LOW_SCORE | 有期望文档未覆盖且无过滤；或期望片段未全部覆盖 |

说明：文档级覆盖=返回命中中至少一条 documentId ∈ expectedDocumentIds；片段级覆盖=全部 expectedChunkIds 均出现在返回的逻辑 chunkId 集合中。

### 2. HIGH_NOISE（高噪声）
相关性口径：文档级（hit.documentId ∈ expectedDocumentIds）。judgeable=expectedDocumentIds 或 mustNotReturnDocumentIds 非空。

| 原因码 | 触发条件 |
|---|---|
| HIGH_NOISE_PRECISION | judgeable 且返回>0 且 precision=relevant/returned < 0.5（阈值冻结于 evaluator-config.json，禁止看结果后调整） |
| HIGH_NOISE_TOP1 | precision≥0.5 但 Top1 不在期望文档内；或不可判 precision 但有期望文档且 Top1 不在期望内 |

### 3. FABRICATED_CITATION（伪造/不合法引用）
仅 HTTP 模式 ask SUCCESS 时静态校验 sources，问题码每类至多一个，取首个作为原因码：

| 原因码 | 触发条件 |
|---|---|
| CITATION_OWNERSHIP_MISMATCH | sourceId 缺失或 ≠ documentId（稳定来源标识必须映射真实文档） |
| CITATION_UNKNOWN_ID | citationId 缺失、≠ sourceRef、不匹配 `S\d+`；或回答文本引用了 sources 之外的编号 |
| CITATION_UNSUPPORTED | quotedSnippet 缺失或不是该 chunk 真实内容的子串（冻结语料逐字核验）；注入用例回答出现执行迹象（回显 UNTRUSTED_EVIDENCE 边界标记或系统提示泄露特征）也记本码并置 injectionExecutionSuspected=true |
| CITATION_FABRICATED_URL | url 非 null 且未通过生产 UrlSafetyPolicy.sanitize（原文件只读编译复用） |

### 4. REFUSAL_ERROR（拒答错误）
仅决策可判（ask SUCCESS）时判定。受控拒答码集合 REFUSE_CODES={NO_EVIDENCE, LOW_RELEVANCE, INSUFFICIENT_EVIDENCE}（与 RagErrorCode 真实枚举一致）。

| 原因码 | 触发条件 |
|---|---|
| REFUSAL_FALSE_NEGATIVE | 期望 ANSWER 实际 REFUSE（误拒） |
| REFUSAL_FALSE_POSITIVE | 期望 REFUSE 实际 ANSWER（误答）；或期望 REFUSE 且 mustNot 文档出现在返回 sources（答案级违反） |
| REFUSAL_WRONG_CODE | 期望 REFUSE 实际 REFUSE 但 errorCode ∉ REFUSE_CODES |

### 5. WRONG_CLARIFICATION（澄清错误）
| 原因码 | 触发条件 |
|---|---|
| CLARIFY_EXPECTED_MISSING | 期望 CLARIFY 实际非 CLARIFY（含系统以 LOW_RELEVANCE 拒答代替澄清——如实记录，不回改标注） |
| CLARIFY_UNEXPECTED | 期望非 CLARIFY 实际 CLARIFY |

### 6. METADATA_INCOMPLETE（元数据不完整）
| 原因码 | 触发条件 | 是否判 FAIL |
|---|---|---|
| METADATA_REQUIRED_MISSING | 任一 source 缺必填字段（sourceId/citationId/sourceRef/chunkId/documentId/title/quotedSnippet/similarity/distance） | 是 |
| PAGE_LOCATION_MISSING | 任一 source 缺 page | **否，仅统计**（与生产契约一致：无定位隐藏不推断） |

### 7. TIMEOUT（超时，按阶段）
| 原因码 | 触发条件 |
|---|---|
| TIMEOUT_RETRIEVAL | 单题检索超过 30s（冻结配置） |
| TIMEOUT_GENERATION | 生成阶段 FAILED_TIMEOUT |
| TIMEOUT_TOTAL | 端到端超过 60s（冻结配置） |

超时计 FAIL 但不中断整轮评测；后续题目继续。

### 8. MODEL_ERROR（模型错误）
| 原因码 | 触发条件 |
|---|---|
| MODEL_EMPTY | generationStatus=FAILED_ERROR 且 errorCode=EMPTY_RESPONSE |
| MODEL_ERROR_GENERIC | generationStatus=FAILED_ERROR 其他错误码 |

**NOT_CONFIGURED/MODEL_NOT_CONFIGURED 是离线受控态，不触发本标签**（mock 无 ChatModel Bean 属预期）。

### 9. RETRIEVAL_ERROR（检索失败）
| 原因码 | 触发条件 |
|---|---|
| RETRIEVAL_EMBED_FAIL | retrievalStatus=FAILED 且 reasonCode=RETRIEVAL_EMBED_FAIL |
| RETRIEVAL_BACKEND_FAIL | retrievalStatus=FAILED 其他原因；或 chunkId 逻辑映射歧义（mappingAmbiguous） |

**成功空结果（EMPTY）绝不触发本标签**——检索空结果≠检索失败。

## 二、四态状态判定（优先级从高到低）

1. **BLOCKED**：检索后端不可用（应用在线但检索探测失败/PG 不可达）。独立态，不计 FAIL。
2. **FAIL**：任一上述判 FAIL 标签命中。
3. **NOT_RUN**：决策未执行（离线内存后端/ask 失败超时）且该题无任何检索类期望可判（QF-001/002 无期望文档与 mustNot）。
4. **PASS**：其余（含"检索质量全过、决策 NOT RUN"的题——决策未测不算失败，也不算决策通过）。

退出码：全 PASS=0；有 FAIL=1；仅 BLOCKED 或 NOT RUN=2。

## 三、自测覆盖

`SelfTest.java` 对 9 标签各含正反触发用例、EMPTY≠FAILED、BLOCKED 独立态、NOT_CONFIGURED 非错误、
PAGE_LOCATION_MISSING 仅统计等边界共 75 项断言，先于正式评测执行，全 PASS 才允许评测（metrics-selftest.txt 留痕）。
