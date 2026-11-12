# D29 模型切换与 indexVersion 证据

| 阶段 | vectorSpaceId | indexVersion |
|---|---|---|
| 初始 | local|local-hashing-embedding|local-v1|256 | 1 |
| 切换后 | openai|text-embedding-3-small|v1|1536 | 2 |

> 切换到不同向量空间必须建立新索引或重新 Embedding，旧向量不混合；本证据仅验证切换逻辑，不代表真实接入 OpenAI。
