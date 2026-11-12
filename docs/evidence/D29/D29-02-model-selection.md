# D29 模型选型与可调用性取证

## 1. 环境事实

- 仅存在 DeepSeek 聊天模型 starter 与 DeepSeek 密钥；无 OpenAI 等密钥。
- `api.openai.com` 不可达；本机无 Ollama；DeepSeek 官方无 Embedding 端点。

## 2. 候选对比（官方数据，价格查询日期 2026-11-12）

| 模型 | 维度 | 费用 | 调用方式 | 当前可调用 |
|---|---|---|---|---|
| OpenAI text-embedding-3-small | 1536 | $0.02/1M token | 云端 API | 否 |
| OpenAI text-embedding-3-large | 3072 | $0.13/1M token | 云端 API | 否 |
| BAAI/bge-m3 | 1024 | 免费（2.27GB/568M 参数） | 本地推理 | 否（无推理环境） |
| **本地哈希词面模型** | 256（可配） | 免费 | 本地 JVM | **是** |

## 3. 结论

当前环境唯一真实可调用方案为本地哈希词面模型 `LocalHashingEmbeddingModel`：
实现 Spring AI `AbstractEmbeddingModel`，真实计算 float 向量，零网络/密钥/费用。
诚实定位为词面（lexical）模型，非神经语义模型；零字面重叠同义词相似度为 0（见 D29-04）。

## 4. 官方溯源

- OpenAI 定价：https://openai.com/api/pricing/
- OpenAI 新 Embedding 模型说明：https://openai.com/index/new-embedding-models-and-api-updates/
- BAAI/bge-m3（Hugging Face）：https://hf.co/BAAI/bge-m3
- DeepSeek 官方文档（无 Embedding 端点）：https://api-docs.deepseek.com/zh-cn/
