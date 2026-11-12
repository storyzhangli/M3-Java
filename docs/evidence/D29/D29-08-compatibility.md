# D29 索引兼容性证据（fail-closed）

当前索引：`local|local-hashing-embedding|local-v1|256`

| 候选向量空间 | 维度 | 结论 | 可写入 |
|---|---|---|---|
| local|local-hashing-embedding|local-v1|256 | 256 | COMPATIBLE | true |
| local|local-hashing-embedding|local-v1|512 | 512 | DIMENSION_MISMATCH | false |
| openai|text-embedding-3-small|v1|256 | 256 | MODEL_SPACE_MISMATCH | false |

> 维度相同但 vectorSpaceId 不同，默认隔离；维度相同不是兼容的唯一依据。
