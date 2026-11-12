# D29 脱敏配置与密钥边界

| 配置项 | 值（脱敏） |
|---|---|
| provider | local |
| model | local-hashing-embedding |
| modelVersion | local-v1 |
| dimensions | 256 |
| maxInputLength（UTF-16 字符，非 Token） | 8192 |
| distanceMetric | COSINE |
| maxAttempts | 3 |
| baseUrl | （空） |
| apiKey | （空） |
| vectorSpaceId | local|local-hashing-embedding|local-v1|256 |

> 本地模型零网络、零密钥、零费用；apiKey 仅在切换到云端模型时通过环境变量注入，禁止硬编码或写入日志/文档。
