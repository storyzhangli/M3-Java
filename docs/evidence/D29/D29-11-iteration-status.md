# D29 迭代状态

## 1. 已完成

- 选定当前环境真实可调用模型：本地哈希词面模型，记录模型/维度/费用/密钥边界。
- 文本→向量调用组件（复用 Spring AI EmbeddingModel/AbstractEmbeddingModel）。
- 向量校验：维度一致、非空、无 NaN/Infinity、L2 归一化。
- 空文本/空白/超长异常处理（超长拒绝，不静默截断）。
- 临时故障有限重试（指数退避，区分永久错误，次数有上限）。
- 模型元数据、vectorSpaceId 与索引兼容性（异维度拒绝、同维度异模型隔离、切换递增 indexVersion）。
- 真实编译测试通过：266 全绿。

## 2. 诚实缺口与边界

- 未实现完整 VectorStore/向量数据库/RAG 检索问答/混合检索/Rerank/Agent/Tool/MCP。
- 本地模型为词面模型，零字面重叠同义词相似度为 0，不具备神经语义能力。
- 字符数（UTF-16）不等于 Token 数；未引入 Tokenizer。
- 真实 MySQL 端到端 HTTP 上传未做（沿用 D27/D28 缺口）；当前无鉴权。
- 云端模型切换仅验证逻辑，未真实接入 OpenAI（网络不可达、无密钥）。

## 3. 版本控制

- 仅执行 git status/diff 复核，未 commit、未 push。
