# D32 相似度检索与 Top-K 技术原理与实践指南

> 适用版本：Spring AI 1.1.8、Spring Boot 3.5.16、PostgreSQL 18.7、pgvector 0.8.7、JDK 21
> 向量配置：384 维、COSINE 距离、HNSW 索引、本地词面 Embedding（local-hashing-embedding/local-v1）
> 阅读路径：是什么 → 为什么 → 底层原理 → 执行流程 → 核心代码 → 常见问题 → 工程实践

## 1. Embedding 与 Query Embedding

### 1.1 是什么

Embedding（嵌入向量）是把离散文本映射为固定长度稠密向量的表示。入库时每个 Chunk 被编码为一个向量；检索时用户问题经同一模型编码为 **Query Embedding（查询向量）**，再用向量距离度量问题与各 Chunk 的接近程度。

### 1.2 为什么必须同模型

向量的每个分量含义由具体模型决定。不同模型即便维度相同，向量空间也互不兼容——直接比较会得到无意义结果。因此 Query Embedding 必须与入库 Chunk 使用**同一 provider、同一 model、同一 modelVersion、同一维度**。本项目用 `vectorSpaceId = provider|model|modelVersion|dimensions` 作为空间指纹，并在结果中回显以便核对。

### 1.3 本地词面模型的能力边界

当前 `TextFeatureExtractor`（包 `embedding.local`）只刻画字面共现，包含三类特征：

- `w`：连续字母数字小写词；
- `c`：每个非空白字符；
- `g`：相邻非空白字符 bigram。

它**没有语义理解**：不识别同义词、上下位、术语别名。零字面重叠时 cosine distance = 1，会被 `distance < 1` 排除。这解释了「气蚀/噪声」无法桥接「异响」的漏召回。

## 2. 向量空间一致性与维度约束

### 2.1 维度约束

维度由 `EmbeddingProperties.dimensions=384` 统一驱动，PG 列定义为 `embedding vector(384)`，HNSW 索引建立在 `vector_cosine_ops` 上。维度不一致会导致写入/检索失败，因此校验链在查询侧显式核对维度，不依赖「维度相同即兼容」的假设。

### 2.2 fail-closed 校验

查询向量化后依次校验：非空、维度等于 384、无 NaN/Infinity、非全零；任一不满足即拒绝，不返回降级的虚假结果。

## 3. Cosine 相似度、距离与排序语义

### 3.1 数学定义

余弦相似度衡量两向量方向一致性：

```text
cosine_similarity(a, b) = (a · b) / (||a|| × ||b||)
cosine_distance(a, b)    = 1 - cosine_similarity(a, b)
```

- 相似度取值 [-1,1]，在非负向量（本地 hashing 特征）下实际落在 [0,1]，越接近 1 越相似；
- 距离取值 [0,1]，越接近 0 越相似。

### 3.2 排序方向

pgvector COSINE 距离运算符为 `<=>`，框架按距离**升序**（即相似度降序）排序，取前 K 条。

### 3.3 分数如何产生（字节码取证）

`PgVectorStore.DocumentRowMapper` 中：

- `getFloat("distance")` 读取 PG 计算的距离，放入 metadata（键 `DocumentMetadata.DISTANCE`，字符串值 "distance"）；
- 以 `score = 1 - distance` 构造 `Document` 的 score。

因此 `similarity` 与 `distance` 是同一事实的两种表达，恒满足 `similarity = 1 - distance`。

## 4. Top-K 检索原理与参数边界

### 4.1 原理

Top-K 即从全表按距离排序后截取前 K 个最近邻。借助 HNSW（Hierarchical Navigable Small World）近似最近邻索引，检索复杂度不随全表线性增长，适合大规模向量。

### 4.2 参数边界

| 参数 | 边界 | 工程含义 |
|---|---|---|
| topK | 默认 5，最小 1，最大 20 | 防止任意大值一次拉取过多结果、放大内存与序列化开销 |
| threshold | 0..1，默认 0 | similarity 语义；框架换算为距离边界 `1 - threshold` |

服务层对越界入参做归一化，DTO 层用 Bean Validation 双重约束。

## 5. Spring AI 1.1.8 VectorStore 检索 API

### 5.1 核心抽象

- `VectorStore`：统一向量存储接口，检索方法 `similaritySearch(SearchRequest)` 返回 `List<Document>`；
- `SearchRequest`：通过 Builder 设定 `query、topK、similarityThreshold、filterExpression`；
- `Document`：含 `id、text、metadata、score`；
- `FilterExpressionBuilder`：以链式 `Op` 构造过滤表达式，`.build()` 产出 `Filter.Expression`。

### 5.2 真实代码：构造检索请求

```java
SearchRequest.Builder builder = SearchRequest.builder()
        .query(query)
        .topK(topK)
        .similarityThreshold(threshold);
if (filtered) {
    builder.filterExpression(buildFilterExpression(request));
}
List<Document> documents = vectorStore.similaritySearch(builder.build());
```

### 5.3 真实代码：构造过滤表达式

```java
FilterExpressionBuilder expressionBuilder = new FilterExpressionBuilder();
List<FilterExpressionBuilder.Op> conditions = new ArrayList<>();
if (isNotBlank(request.getDocumentId())) {
    conditions.add(expressionBuilder.eq("documentId", request.getDocumentId().trim()));
}
if (request.getVersion() != null) {
    conditions.add(expressionBuilder.eq("version", request.getVersion()));
}
FilterExpressionBuilder.Op combined = conditions.get(0);
for (int i = 1; i < conditions.size(); i++) {
    combined = expressionBuilder.and(combined, conditions.get(i));
}
return combined.build();
```

编译要点：`eq/and` 返回 `Op` 而非 `Filter.Expression`；`and` 是二元且形参为 `Op`，多条件需嵌套归约，最后统一 `.build()`。

## 6. PgVectorStore 检索与元数据过滤

### 6.1 关键纠偏：`==` 是 jsonpath 运算符而非裸 SQL

早期误判框架 EQ 过滤器会因 `==` 报错。经探针 `ConvProbe` 实证，过滤被渲染为 PostgreSQL **SQL/JSON Path** 谓词：

```text
metadata::jsonb @@ '$."documentId" == "d32it-air"'::jsonpath
metadata::jsonb @@ '$."version" == 1'::jsonpath
```

- `metadata`（json）先 `::jsonb` 转换；
- `@@` 是 SQL/JSON Path 匹配运算符，在 WHERE 内执行；
- `==`（相等）、`&&`（逻辑与）、`||`（逻辑或）均为 **jsonpath 内部运算符**。SQL/JSON 标准路径语言的相等运算符就是 `==`，故合法。
- 裸 SQL 中 `SELECT 1 == 1` 报错，是因为那不在 jsonpath 语境，二者不可混淆。

### 6.2 真实下推

过滤片段拼入 PG SQL 的 WHERE，由数据库在扫描/索引阶段执行，属于**真实下推**而非应用层内存过滤。这减少了返回行数并保证版本/来源约束在库端生效。

### 6.3 过滤下推流程

```mermaid
graph TD
    A["Filter.Expression 逻辑条件"] --> B["PgVectorFilterExpressionConverter"]
    B --> C["渲染 jsonpath 字符串"]
    C --> D["metadata::jsonb @@ jsonpath"]
    D --> E ["拼入 PG SQL WHERE"]
    E --> F ["HNSW 检索 + 过滤同时执行"]
    F --> G ["返回命中 Document"]
```

## 7. 来源信息与分数解释

### 7.1 来源字段

`SimilarityMatchView` 从真实 metadata 读取 `documentId、title、version、strategy、chunkIndex`，并携带 `chunkId`（D31 UUID v5 稳定 ID）与完整 metadata。字段缺失即置 null，不臆造。

### 7.2 分数解释纪律

| 字段 | 口径 | 禁止事项 |
|---|---|---|
| similarity | `Document.getScore()`，1 - distance | 禁止把 distance 当 similarity |
| distance | metadata 的原始余弦距离 | 禁止手工归一化或编造 |

适配器未暴露分数时应如实返回 null 并说明能力限制，而不是伪造。

## 8. 检索噪声、漏召回与人工评估方法

### 8.1 噪声与漏召回

- **噪声**：返回但不相关的片段。词面模型下，共享「故障代码/设备型号」等通用词会把同表其他条目拉高，形成高分噪声。
- **漏召回**：目标相关片段未进入 Top-K。本迭代确认 1 项：Q3 的气蚀片段（`0533ec49-ca47-59fd-9e9b-42b0611fd5ee`），根因是同义词/术语差异。

### 8.2 人工评估方法

1. 每题固定 Top-5、阈值 0，保存完整结果（chunkId、分数、正文、metadata）；
2. 逐条标注 R（相关）/ P（部分相关）/ N（不相关），口径事先固定；
3. 漏召回必须有可核对的真实目标片段，无法确认标「待确认」；
4. **AI 初判与人工复核分列**，AI 标注不得冒充人工结论。

### 8.3 本批次实测分布

| 类别 | 相似度区间 | 数量 |
|---|---|---|
| R | 0.3166 – 0.5379 | 14 |
| P | 0.1546 – 0.4070 | 12 |
| N | 0.1124 – 0.4103 | 24 |

Rank1 命中率 10/10 = 100%；严格 R 率 28%，R+P 有帮助率 52%。

## 9. 相似度阈值为何要基于数据评估

### 9.1 观测证据

- 高分噪声：Q6 Rank2 不相关片段 0.4103、Q5 Rank2 0.3971；
- 低分相关：Q10 Rank1 相关片段 0.3166、Q3 Rank1 0.3198；
- R 与 N 在 0.32–0.41 区间重叠，且不同问题分数基线不同。

### 9.2 结论

单一全局阈值无法同时满足「去噪」与「保留低分相关」：阈值偏高误杀低分相关，阈值偏低保留高分噪声。因此本阶段不设阈值，先依赖排序 + 人工复核；阈值确定需更大规模、跨主题且完成人工标注的数据支撑，并应按业务场景（而非全表统一）评估。

## 10. 常见问题（FAQ）

1. **为什么内容最完整的答案不一定排第一？** 词面模型按字面共现打分，不识别答案完整度，短条目可能因共现率高而靠前。
2. **`==` 会不会报 SQL 语法错？** 在 jsonpath 语境合法；只有裸 SQL 才报错。
3. **字符数等于 Token 吗？** 不等。本项目长度口径是 UTF-16 字符，配置已明确，不能等同 Token。
4. **维度相同就能混用模型吗？** 不能，还需 provider/model/version 一致，以 vectorSpaceId 为准。
5. **无结果怎么办？** 正常返回空 matches 与 total=0；只有服务/数据库不可用时才停止真实验收，不用 Mock 冒充。

## 11. 工程实践清单

- 复用既有 VectorStore/EmbeddingModel，不引入第二套向量库，不重复入库逻辑；
- 入参边界 + 服务归一 + fail-closed 校验三重保障；
- 日志记录 queryLength、topK、threshold、filtered、total、维度，不打印大段向量与密钥；
- 密码经环境变量注入，证据/文档脱敏；
- 不提前构建 RAG/LLM/Tool/MCP/Agent，保持检索能力边界清晰。

## 12. 记忆简语与层级树

记忆简语：**同模型同空间，三八四维度；距离升序、相似降序、Top 五；jsonpath 真下推、`==` 不报错；分数 1 减距离、不编造；阈值看数据、不拍脑袋。**

层级树：

```text
相似度检索技术栈
├─ 表示层 Embedding
│  ├─ 词/字符/bigram 特征
│  └─ Query Embedding (384)
├─ 度量层 COSINE
│  ├─ distance = 1 - similarity
│  └─ HNSW vector_cosine_ops
├─ 接口层 Spring AI
│  ├─ VectorStore.similaritySearch
│  ├─ SearchRequest (topK/threshold/filter)
│  └─ FilterExpressionBuilder (Op → build)
├─ 下推层 PgVectorStore
│  └─ metadata::jsonb @@ jsonpath
└─ 评估层
   ├─ R/P/N 标注 + 人工复核
   └─ 阈值基于数据评估
```

## 13. 官方资料溯源（英文原标题 + 中文翻译 + 链接）

1. 《Spring AI Reference Documentation》（Spring AI 参考文档）：https://docs.spring.io/spring-ai/reference/
   （框架 API 以本地 1.1.8 依赖 jar 与字节码核对为准。）
2. 《PostgreSQL Documentation — JSON Functions and Operators》（PostgreSQL 文档 — JSON 函数与运算符，含 `@@` 与 jsonpath）：https://www.postgresql.org/docs/current/functions-json.html
3. 《PostgreSQL Documentation — JSON Type / SQL/JSON Path Language》（PostgreSQL 文档 — JSON 类型与 SQL/JSON 路径语言，jsonpath 相等运算符 `==`）：https://www.postgresql.org/docs/current/datatype-json.html
4. 《pgvector — Open-source vector similarity search for Postgres》（pgvector — Postgres 开源向量相似度检索，含 `<=>` 余弦距离运算符）：https://github.com/pgvector/pgvector
