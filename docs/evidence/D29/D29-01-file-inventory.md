# D29 文件清单与改动范围

## 1. 新增主源（21 个，物理包 com.zl.d29.document.embedding）

| 子包 | 文件 | 职责 |
|---|---|---|
| config | EmbeddingProperties | app.ai.embedding 配置与启动校验 |
| config | EmbeddingConfiguration | 装配 EmbeddingModel Bean |
| controller | EmbeddingController | 模型元数据/向量化/兼容性入口 |
| dto | CompatibilityCheckRequest | 兼容性检查请求体 |
| dto | CompatibilityDecisionView | 兼容性结论视图 |
| dto | EmbeddingModelMetadataView | 模型元数据视图 |
| dto | EmbeddingRecordView | 向量记录视图（预览，不 dump 全量） |
| dto | EmbeddingViewMapper | 领域模型 → 视图映射 |
| local | FeatureHasher | FNV-1a 特征哈希（桶+符号） |
| local | LocalHashingEmbeddingModel | 本地哈希词面模型（真实计算） |
| local | TextFeatureExtractor | 词/字符/bigram 特征提取 |
| model | CompatibilityDecision | 兼容性结论枚举 |
| model | DistanceMetric | 距离度量枚举 |
| model | EmbeddingModelMetadata | 向量空间/索引元数据 |
| model | EmbeddingRecord | 单 Chunk 向量记录 |
| service | DocumentEmbeddingService | Chunk 批量向量化 |
| service | VectorIndexCompatibilityService | 索引兼容性与模型切换 |
| support | EmbeddingFailureClassifier | 临时/永久故障分类 |
| support | EmbeddingRetryExecutor | 有限重试（指数退避） |
| support | EmbeddingServiceException | 统一异常（可重试标识） |
| support | VectorValidator | 维度/非空/NaN/Infinity 校验 |

## 2. 新增测试（8 个，含 1 个受控桩）

| 文件 | 类型 |
|---|---|
| EmbeddingPropertiesTest | 配置校验 |
| LocalHashingEmbeddingModelTest | 真实模型计算 |
| VectorValidatorTest | 向量校验 |
| EmbeddingRetryExecutorTest | 重试逻辑（受控） |
| DocumentEmbeddingServiceTest | 服务层（真实+受控） |
| VectorIndexCompatibilityServiceTest | 兼容性逻辑 |
| EmbeddingControllerTest | MockMvc 切片 |
| StubEmbeddingModel | 受控测试桩（非真实第二模型） |

## 3. 改动边界

- 未修改任何既有业务类、POM 依赖、公共接口与配置文件；无新增第三方依赖。
- 未实现 VectorStore、向量数据库、RAG 检索问答、混合检索、Rerank、Agent、Tool、MCP。
