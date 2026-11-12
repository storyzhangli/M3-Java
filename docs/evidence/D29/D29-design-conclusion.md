# D29 设计结论：Embedding 原理与模型选择

## 1. 真实环境条件（已取证）

- 技术基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、Maven 3.9.16。
- 现有模型 starter：仅 `spring-ai-starter-model-deepseek`（聊天模型）。
- 密钥：仅存在 DeepSeek 体系密钥（`sk-` 前缀，35 字符）；无 OpenAI 等其它服务商密钥。
- 网络：`api.deepseek.com` 可达；`api.openai.com` 不可达；本机无 Ollama。
- 官方核实：DeepSeek 官方 API 文档（api-docs.deepseek.com）模型清单仅含聊天模型
  （deepseek-flash、deepseek-v4-pro）与 Search/Fetch，**不提供 Embedding 端点**；
  第三方站点所谓“DeepSeek Embeddings”非官方内容，不予采信。

## 2. 候选模型对比（官方数据，价格查询日期 2026-11-12）

| 模型 | 提供方 | 调用方式 | 维度 | 中文能力 | 最大输入 | 费用 | 当前可调用 |
|---|---|---|---|---|---|---|---|
| text-embedding-3-small | OpenAI | 云端 API/Spring AI | 1536 | 支持 | 8192 token | $0.02/1M token | 否（网络不可达+无密钥） |
| text-embedding-3-large | OpenAI | 云端 API/Spring AI | 3072 | 强 | 8192 token | $0.13/1M token | 否 |
| BGE-M3（BAAI/bge-m3） | 本地开源 | 本地推理 | 1024 | 强（170+ 语言） | 8192 token | 免费，需 2.27GB 模型/约 568M 参数 | 否（无本地推理环境/Ollama） |
| **本地哈希词面向量（本轮主模型）** | 本地 JVM | Spring AI EmbeddingModel | 可配，默认 256 | 字面级（按字符/词特征） | 由 maxInputLength 控制 | 免费、零网络、零密钥 | **是** |

## 3. 主模型选择与理由

选择**本地哈希词面向量模型（LocalHashingEmbeddingModel）**作为本轮主模型，原因：

1. 它是当前环境**唯一真实可调用**的方案：纯 JVM 计算、零网络、零密钥、零费用。
2. 实现 Spring AI `AbstractEmbeddingModel`，是真实计算向量的本地模型，不是 Mock；
   工程抽象（配置/校验/重试/元数据/兼容性）与具体模型解耦，后续可配置切换到云端模型。
3. 诚实定位：它是**词汇/字面向量（lexical embedding）**，具备字面相似度能力，
   但不是 BGE-M3 这类神经语义模型；零字面重叠的同义句相似度趋近 0，这一局限本身就是教学证据。

## 4. 向量空间与兼容性设计

- 向量空间标识 `vectorSpaceId = provider + "|" + model + "|" + modelVersion + "|" + dimensions`。
- 索引元数据：provider、modelName、modelVersion、dimensions、vectorSpaceId、indexVersion、distanceMetric。
- 写入规则（fail-closed）：
  - 维度不同 → 拒绝写入同一索引；
  - 维度相同但 vectorSpaceId 不同（不同模型/版本）→ 默认隔离，禁止混合；
  - 维度相同绝不作为兼容的唯一依据；
  - 模型切换需新索引或重新 Embedding，indexVersion 递增。

## 5. 长度与数值口径

- 长度单位沿用 UTF-16 code unit；Embedding 输出为 `float[]`，默认 L2 归一化，距离度量默认 COSINE。
- 字符数不等于 Token 数；本轮不引入 Tokenizer，不宣称 Token。

## 6. 组件与测试方案

- 组件：EmbeddingProperties、LocalHashingEmbeddingModel、VectorValidator、EmbeddingRetryExecutor、
  DocumentEmbeddingService、VectorIndexCompatibilityService、EmbeddingController + 视图。
- 真实验证：本地模型真实嵌入中文/中英混合/长短文本，校验维度、归一化、无 NaN/Infinity、余弦相似度。
- 受控验证：重试（临时故障/永久故障/次数耗尽）与兼容性（异维度/同维度异模型/切换）用受控桩与元数据，
  明确标注为模拟测试，不声称真实接入第二个模型。

## 7. 硬边界

不实现完整 VectorStore、向量数据库部署、RAG 检索/问答、混合检索、Rerank、Agent、Tool Calling、MCP。
