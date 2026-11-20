# D37 评测汇总（由原始记录复算生成）

- 生成时间：2026-11-20T19:01:06.476912600+08:00
- 模式：OFFLINE；检索后端：IN_MEMORY_LOCAL
- 降级/判定原因：应用未启动，降级内存索引；离线原因：AI_MODE 非 deepseek;无 ChatModel;
- 受控参数（EVAL_CONTROLLED_BASELINE）：topK=5，threshold=0.0（显式传入，不依赖应用默认值）
- 预热 3 轮（不计）；正式 3 轮；原始记录 60 条（每条带 retrievalBackend）
- 轮间质量一致性：全部一致

## 四态计数（合计=20，应等于 20）

| PASS | FAIL | BLOCKED | NOT RUN |
|---|---|---|---|
| 1 | 17 | 0 | 2 |

## 总体指标（分母 0 = N/A）

| 指标 | 值 |
|---|---|
| hitRateAtK | 1.0000 |
| recallMacro | 1.0000 |
| precisionMacro | 0.2222 |
| noiseMacro | 0.7778 |
| refusalAccuracy | 0.0000 |
| falseRefusalRate | 0.0000 |
| falseAnswerRate | 0.0000 |
| clarifyAccuracy | 0.0000 |
| citationLegality | N/A |
| metadataCompleteness | N/A |

分母口径：refuseExpected=8 answerExpected=10 unanswerable=8 clarifyExpected=2 withSources=0 sourcesTotal=0

## 标签分布

- HIGH_NOISE:HIGH_NOISE_PRECISION × 17

## 逐题状态

| 题号 | 场景 | 期望 | 实际 | 状态 | 标签 |
|---|---|---|---|---|---|
| QA-001 | ANSWERABLE | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QA-002 | ANSWERABLE | ANSWER | NOT_RUN | PASS |  |
| QA-003 | ANSWERABLE | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QA-004 | ANSWERABLE | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QA-005 | ANSWERABLE | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QU-001 | UNANSWERABLE | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QU-002 | UNANSWERABLE | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QU-003 | UNANSWERABLE | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QU-004 | UNANSWERABLE | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QU-005 | UNANSWERABLE | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QT-001 | TERMINOLOGY | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QT-002 | TERMINOLOGY | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QT-003 | TERMINOLOGY | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QT-004 | TERMINOLOGY | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QT-005 | TERMINOLOGY | ANSWER | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QF-001 | BOUNDARY_FUZZY | CLARIFY | NOT_RUN | NOT_RUN |  |
| QF-002 | BOUNDARY_FUZZY | CLARIFY | NOT_RUN | NOT_RUN |  |
| QF-003 | BOUNDARY_FUZZY | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QF-004 | BOUNDARY_FUZZY | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |
| QF-005 | BOUNDARY_FUZZY | REFUSE | NOT_RUN | FAIL | HIGH_NOISE:HIGH_NOISE_PRECISION |

## 已知偏差

- Embedding 为本地哈希词法模型（local-hashing-embedding/local-v1/384/COSINE），非神经语义模型；结论不得外推到神经 Embedding。
- 语料仅 3 文档 8 片段、20 问，统计功效有限；单标签标注下噪声占比不等价人工全量相关性评估。
- 跨后端（PGVECTOR vs IN_MEMORY_LOCAL）结果不可直接比较；内存耗时不是数据库耗时。
- 离线模式下生成类指标 NOT RUN（mock 无 ChatModel，NOT_CONFIGURED 为受控态）。
