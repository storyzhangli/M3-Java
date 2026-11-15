# D32 知识笔记：相似度检索与 Top-K

> 技术基线：JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / PostgreSQL 18.7 / pgvector 0.8.7  
> 适用场景：制造设备故障知识库 RAG  
> 关键词：Cosine Distance、Top-K、Similarity Threshold、Metadata Filter、HNSW、Recall@K

## 0. 本项目基线与证据边界

根据当前仓库 `master` 的 D31 源码、配置和 2026-11-14 数据库状态快照核对：

| 项目 | 已核对值 | 说明 |
|---|---|---|
| Spring AI | 1.1.8 | 由 `D31/pom.xml` 的 `spring-ai-bom` 锁定 |
| PostgreSQL / pgvector | 18.7 / 0.8.7 | 来自仓库内只读数据库快照，不代表本次重新连接数据库 |
| Embedding | `local-hash-ngram-v1` / `2026.11` | 本地确定性哈希词/字符 n-gram 模型，不是预训练神经语义模型 |
| 向量维度 | 384 | 配置、模型描述和 Embedding 证据一致 |
| 距离度量 | COSINE | 向量存储配置仅允许 COSINE |
| 向量表 | `public.vector_store` | Spring AI PgVectorStore 默认表名，配置显式使用该表 |
| 向量类型 | `vector(384)` | D31 数据库证据摘要记录的类型 |
| 向量索引 | HNSW + `vector_cosine_ops` | `spring_ai_vector_index`，与 `<=>` 匹配 |

**当前检索代码的实际行为：**

1. `KnowledgeVectorStoreService` 默认 `topK=4`，最大 50；调用方传入 `topK<=0` 时项目服务回退为 4，超过 50 会被截断。
2. `similarityThreshold=null` 时传入 `SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL`，即 `0.0`，不执行有效的相似度门槛过滤。
3. 当前检索请求没有调用 `.filterExpression(...)`，即没有通过 `SearchRequest` 添加 Metadata Filter。
4. 当前向量文档元数据包括 `documentId`、`title`、`version`、`chunkIndex`、`strategy`、`pageNumbers`、`characterCount`、`vectorSpaceId`、`modelName`。
5. `equipmentModel`、`documentType`、`faultCode`、`tenantId` 等字段目前不在该检索服务构造的 Metadata 中；不能直接假设这些字段已可过滤。
6. 仓库快照显示 `public.vector_store` 在 2026-11-14 时为 0 行，D31 的独立集成测试向量表当时也为 0 行。因此，本笔记提供了可复现 SQL，但**没有声称本次已完成 D32 的真实检索、阈值、ANN 召回率或性能实验**。

特别说明：项目存在旧的内存关键词检索 `KnowledgeRetrievalService`，它不是 pgvector 向量检索。向量检索应看 `KnowledgeVectorStoreService` 与 `PgVectorStore` 路径。项目另有 D31 索引编排服务；本笔记不重复展开其入库幂等流程。

## 1. 相似度与距离

### 1.1 Embedding 与向量空间

Embedding 模型将文本映射为固定长度的数值向量。例如：

- Query：`数控机床出现主轴过热报警时，先检查什么？`
- Chunk：`数控机床出现主轴过热报警时，应先检查冷却液液位与冷却泵运行声音，再检查散热器滤网是否堵塞……`

模型输出的向量保留了模型所学习或构造的特征。检索时，系统把 Query 与每个 Chunk 转成向量，再依据选定距离函数排序。

**Query 向量与库内 Document 向量必须属于兼容的向量空间。** 至少要保持模型、模型版本、维度、预处理方式和向量空间配置兼容。不能用 A 模型生成库内向量，却用 B 模型生成 Query 向量后直接比较，即使两者维度相同，坐标语义也未必一致。

当前项目使用同一 `LocalHashNgramEmbeddingModel` 进行文档向量化和 Query 向量化；模型先对文本做 NFKC 归一化、小写化，再抽取词、字符、二元组和三元组特征，哈希到 384 维并执行 L2 归一化。它适合验证链路可重复性，但不是具有强语义泛化能力的预训练 Embedding 模型。遇到同义改写、跨语言表达或未见过的领域概念时，不应把它的检索表现等同于生产级语义模型。

### 1.2 Cosine Similarity 与 Cosine Distance

余弦相似度衡量两个向量方向的接近程度：

\[
\operatorname{cos\_sim}(A,B)=\frac{A\cdot B}{\|A\|\,\|B\|}
\]

几何直觉：把两个向量视为从原点出发的箭头，余弦值主要衡量两支箭头之间的夹角。方向越接近，余弦相似度越高；方向相反时越低。

余弦距离定义为：

\[
\operatorname{cos\_dist}(A,B)=1-\operatorname{cos\_sim}(A,B)
\]

因此：

- Cosine Similarity：越大越相似，通常从高到低排序。
- Cosine Distance：越小越相似，通常从低到高排序。
- 对非零向量，Cosine Similarity 理论范围为 [-1, 1]，Cosine Distance 范围为 [0, 2]。
- 全零向量没有可定义的方向；pgvector 的 Cosine Distance 在任一向量全零时会得到 NaN，Embedding 应拒绝零向量。

pgvector 运算符：

| 运算符 | 含义 | 排序方向 | 典型 Operator Class |
|---|---|---|---|
| `<=>` | Cosine Distance | ASC，越小越近 | `vector_cosine_ops` |
| `<->` | Euclidean / L2 Distance | ASC，越小越近 | `vector_l2_ops` |
| `<#>` | Negative Inner Product | ASC，越小代表原始点积越大 | `vector_ip_ops` |

Cosine 相似度可用 `1 - (a <=> b)` 计算。千万不要把距离越大越近，也不要对 `<#>` 直接按原始点积的排序直觉排序；`<#>` 返回的是负点积，升序才对应点积最大优先。

### 1.3 L2、Inner Product 与 Cosine 的区别

- **Cosine**：关注方向，不直接受向量长度比例影响。常用于文本 Embedding 的相似度检索。
- **L2**：计算向量空间中的直线距离。向量的方向和模长都会影响结果。
- **Inner Product**：计算点积。若向量未归一化，点积会同时受到方向和向量长度影响。

对于单位长度向量：

\[
A\cdot B=\operatorname{cos\_sim}(A,B)
\]

且：

\[
\|A-B\|_2^2=2-2\operatorname{cos\_sim}(A,B)
\]

因此在向量都经过 L2 归一化时，Cosine、点积和 L2 的**排序结果一致**（L2 距离是余弦相似度的单调变换）。但原始数值并不相同，阈值不能从一个度量直接复制到另一个度量。

当前本地模型会归一化输出向量；D31 的本地 Embedding 证据样本 L2 Norm 约为 1。这是当前模型实现的性质，不代表所有未来替换的 Embedding 模型都一定归一化。

### 1.4 为什么相似度分数不是答案正确概率

Cosine Similarity 只表示两个向量在特定 Embedding 空间中的几何相近程度，不代表：

- Chunk 已经回答了 Query 的全部问题；
- Chunk 的事实准确、未过期或适用于当前设备；
- 模型最终生成的答案有相同概率正确；
- `0.8` 就意味着“80% 的概率正确”。

相似度分数受模型、文本切分、语料分布、Query 表达和领域术语影响。它不是经过概率校准的分类置信度。

## 2. Top-K 检索

### 2.1 Top-K / KNN 是什么

KNN（k-nearest neighbors）按距离函数计算 Query 与候选向量的距离，并取最接近的 K 个结果。Top-K 是返回数量上限，不代表返回结果必然相关。

典型 SQL：

```sql
SELECT
    id,
    content,
    embedding <=> CAST(:query_vector AS vector(384)) AS cosine_distance,
    1 - (embedding <=> CAST(:query_vector AS vector(384))) AS cosine_similarity
FROM public.vector_store
ORDER BY embedding <=> CAST(:query_vector AS vector(384)) ASC
LIMIT :top_k;
```

`:query_vector` 是由当前同一 EmbeddingModel 对 Query 实际生成的 384 维向量字符串；`:top_k` 是绑定参数。上面是 JDBC 命名参数示意，直接在 `psql` 执行时需用实际向量字面量替换占位符，不能用随意编造的向量代替真实 Query Embedding。

### 2.2 K 太小与太大

- K 太小：可能只取到一个局部症状描述，漏掉诊断步骤、危险操作限制或后续处置条件。
- K 太大：容易引入相似但不适用的内容，增加重复 Chunk、噪声和上下文 Token 消耗。
- K 不是越大越好：扩大 K 可能提升召回，但也可能降低 Precision，并挤压模型上下文窗口。

K 应与 Chunk 大小、Chunk 重叠、文档密度、Query 类型和模型上下文预算共同考虑。当前切分配置有固定长度策略，默认 `chunk-size=480`、`overlap=60`（长度单位为 Unicode 码点，并非 Token）。相邻 Chunk 因重叠区可能包含相同信息，Top-K 中可能出现多个几乎重复的相邻片段。

### 2.3 候选集与最终返回集

需要区分三个层次：

1. **全量库**：当前向量表中的所有合格向量。
2. **检索候选集**：精确扫描或 ANN 索引在搜索过程中访问、产生的候选向量。
3. **最终结果集**：在 Metadata 条件、相似度阈值等规则生效后，按距离排序并最多返回 K 条的结果。

ANN 的候选集只是近似搜索结果，不等于全量库的精确最近邻集合。HNSW 的候选集大小与 `ef_search` 等参数相关，不能把它和应用层 Top-K 混为一谈。

### 2.4 K 的调整方法

建立一组真实设备故障 Query 与人工相关性标注，分别测试 K=2、4、6、8、10 等候选值。观察 Recall@K、Precision@K、MRR、NDCG、平均返回条数、重复比例、上下文 Token 和端到端耗时，再决定默认 K。项目当前的 4 是工程默认值，不是经 D32 评估证明的最优 K。

## 3. Similarity Threshold

### 3.1 与 Top-K 的关系

- Top-K：最多返回多少条。
- Threshold：每条结果必须达到的最低相似度要求。

二者目标不同，通常同时使用。假设 Top-K=5、Similarity Threshold=0.72：符合阈值的结果最多返回 5 条；如果只有 2 条达到阈值，就只返回 2 条，不应补进 3 条低于阈值的结果。

**Spring AI 1.1.8 + PgVectorStore 的具体语义：** `SearchRequest` 默认 `topK=4`，`similarityThreshold=0.0`；阈值必须位于 [0,1]。虽然 `SearchRequest` 的通用 Javadoc 将阈值描述为客户端后处理，但 1.1.8 的 `PgVectorStore.doSimilaritySearch()` 对 Cosine 路径实际把它转成 SQL 条件：

```sql
WHERE embedding <=> :query_vector < (1 - :similarity_threshold)
ORDER BY embedding <=> :query_vector
LIMIT :top_k
```

因此本项目 PG 检索实际是数据库侧先过滤阈值，再按距离升序，最后 LIMIT。Cosine 分数 `1 - distance` 要**严格大于**传入阈值才会入选，边界不是 `>=`。`Document.score` 则由 PgVectorStore 的 `1.0 - distance` 赋值；原始距离保存在 Document Metadata 的 `distance` 字段。这里的 Score 是相似度转换值，不是答案正确概率。

项目 `KnowledgeVectorStoreService` 在阈值参数为 null 时传入 0.0，所以默认 accept-all；调用方必须显式传入经过校准的 threshold 才会真正过滤低相关结果。

### 3.2 阈值过高 / 过低

- 阈值过高：相关 Chunk 也可能被挡掉，Recall 下降，甚至没有结果。
- 阈值过低：容易召回泛化段落、主题相关但不能回答问题的片段，Precision 下降。
- 不同模型、模型版本、语料、Chunk 策略和 Query 类型的分数分布不同，不能共用一个未经验证的固定值。

### 3.3 阈值校准步骤

1. 收集真实 Query，例如设备型号 + 故障码、自然语言症状、维修步骤追问。
2. 人工标注每条 Query 的相关文档/Chunk，必要时标注 0/1/2 等分级相关性。
3. 先用精确搜索建立距离排序基准，记录真实相关项的分数分布和不相关项的分数分布。
4. 扫描多个阈值，比较 Recall、Precision、空结果比例和误召回类型。
5. 针对高风险故障、安全操作等场景，采用更严格的证据要求；不能仅依靠一个全局阈值。
6. 模型、索引或切分版本升级后重新校准。

无结果或结果不足 K 时，应返回实际命中数。低相关或无匹配时，应明确告知“当前知识库没有足够可靠的依据”，并考虑请求补充设备型号、故障码或症状，不要为了凑数将弱相关片段交给生成模型。

## 4. Metadata Filter

### 4.1 Metadata 的作用

Metadata 是随 Chunk 保存的结构化属性，用于把向量相似度搜索约束在正确业务范围。常见类型包括字符串、数字、布尔值、日期、版本和类别字段。设备故障知识库可考虑：

- `equipmentModel`：设备型号；
- `documentType`：手册、维修指导书、工单规范等；
- `faultCode`：故障编码；
- `version`：文档版本；
- `tenantId`：租户或组织边界；
- `documentId`：文档主键；
- `vectorSpaceId`：模型向量空间标识。

### 4.2 与 SQL WHERE 的关系

Metadata Filter 本质上是把结构化条件翻译成向量库查询的 WHERE 条件。Spring AI 使用 `SearchRequest.filterExpression(...)` 表达过滤逻辑，PgVectorStore 1.1.8 的 `PgVectorFilterExpressionConverter` 把条件转成 PostgreSQL JSONPath 谓词：

```sql
metadata::jsonb @@ '...'::jsonpath
```

例如 Spring AI 表达式：

```java
SearchRequest request = SearchRequest.builder()
    .query(query)
    .topK(5)
    .similarityThreshold(0.72)
    .filterExpression(
        "documentId == 'DOC-CNC-COOLING-001' && version == 'teaching-fixture-2026.11'")
    .build();
```

当前项目的 `metadata` 列类型为 `json`，PgVectorStore Filter Converter 会转换为 `metadata::jsonb` 后执行 JSONPath 匹配。上面字段必须真实存在于每条向量的 Metadata 中。

直接 SQL 过滤示例：

```sql
SELECT id, content,
       embedding <=> CAST(:query_vector AS vector(384)) AS distance
FROM public.vector_store
WHERE metadata::jsonb ->> 'documentId' = 'DOC-CNC-COOLING-001'
  AND metadata::jsonb ->> 'version' = 'teaching-fixture-2026.11'
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 5;
```

上面的 `->>` 写法用于解释普通 SQL 条件；Spring AI 1.1.8 产生的是 JSONPath `@@` 表达式，两者逻辑目的类似，但执行计划和索引匹配方式可能不同。

### 4.3 当前项目的 Metadata 缺口

当前向量写入服务保存了 `documentId`、`title`、`version`、`chunkIndex`、`strategy`、`pageNumbers`、`characterCount`、`vectorSpaceId`、`modelName`。当前没有在该服务中保存 `equipmentModel`、`documentType`、`faultCode` 或 `tenantId`。因此：

- 可以先对已存在的 `documentId`、`version`、`strategy`、`vectorSpaceId` 做过滤验证。
- 不能仅凭原始语料文件有 `sourceType` 字段，就推断该字段已被写入 PostgreSQL 向量元数据。
- 若后续需要按设备型号、故障码或文档类型筛选，必须在 Document Metadata 生成链路中写入字段，并验证历史向量是否需要补充或重建。

### 4.4 Metadata 索引、组合过滤与分区

当前表上已经核验到 HNSW 向量索引，但没有证据表明已有专门的 Metadata GIN/B-tree 索引。可考虑下列索引方案，必须在副本或评估环境验证后再决定是否用于主表：

```sql
-- 对 Spring AI 1.1.8 JSONPath 过滤形式提供 GIN 索引支持的候选方案
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vector_store_metadata_gin
ON public.vector_store
USING gin ((metadata::jsonb) jsonb_path_ops);

-- 对直接使用 ->> 等值过滤的 SQL 建立表达式 B-tree（不要假设它一定匹配 Spring AI 的 JSONPath SQL）
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vector_store_document_id
ON public.vector_store ((metadata::jsonb ->> 'documentId'));
```

- GIN 适合 JSONB 键值与 JSONPath 条件；`jsonb_path_ops` 支持 `@>`、`@?` 和 `@@`，不支持键存在运算符 `?`。
- B-tree 适合高频等值或范围过滤字段；组合索引列顺序要基于实际 WHERE 条件和选择性决定。
- 部分索引适合少数固定高频类别或固定租户值，不适合盲目为所有值创建。
- 租户规模很大、租户过滤选择性极高时，可评估按 tenant 分区；分区会增加迁移、维护和运维复杂度。
- PostgreSQL 是否采用某个索引取决于统计信息、行数、选择性和成本估计，建立索引不等于查询必然使用。

**权限过滤不是普通业务过滤。** `tenantId`、用户可访问文档集合和 ACL 必须从服务端认证授权上下文生成，不能信任用户请求自行提交的租户 ID，也不能把“向量很相似”当作数据访问授权。真正的隔离条件必须在检索 SQL/服务端过滤阶段强制执行；必要时还要与权限关系表做连接或使用分区/RLS 设计。

### 4.5 Filter 对 ANN 的影响

HNSW/IVFFlat 首先从向量索引中访问候选，再由 PostgreSQL 执行普通 WHERE 过滤；若过滤条件很严格，第一批候选可能大部分被筛掉，结果数量就会少于 LIMIT。pgvector 0.8.0 起提供 iterative index scans，让索引在候选被 WHERE 剔除后继续扫描，直到达到足够结果或触及扫描上限。

对 HNSW 可按事务设置：

```sql
BEGIN;
SET LOCAL hnsw.ef_search = 100;
SET LOCAL hnsw.iterative_scan = relaxed_order;
-- 在这里执行带 Metadata WHERE 的向量检索
COMMIT;
```

`relaxed_order` 允许结果顺序略有放宽；若需要严格按距离展示，可把候选放进 `MATERIALIZED CTE` 后再次按距离排序。`strict_order` 保持严格顺序，但在某些负载下可能牺牲召回或性能。调参应比较最终返回行数、召回率和耗时，而不是只看 SQL 是否执行成功。

## 5. Exact Search、ANN 与 HNSW

### 5.1 精确最近邻与近似最近邻

- **Exact Search**：对过滤后的全部合格向量计算距离并排序，得到真实 Top-K。相对于当前保存的向量，它提供精确最近邻结果，可作为 ANN 评估基准；不代表这些结果对人类问题一定相关。
- **ANN**：借助索引跳过大量向量，快速找到近似最近邻，允许漏掉一部分真正的最近邻，以换取速度和资源效率。

pgvector 在没有使用 ANN 索引时默认执行精确最近邻搜索。加上 HNSW 或 IVFFlat 后，执行计划可能使用近似索引，结果可能与精确搜索不同。表很小或选择性过滤后只剩少量行时，Seq Scan + Sort 可能比 HNSW 更划算。

### 5.2 HNSW 原理

HNSW（Hierarchical Navigable Small World）是一种分层近邻图：

1. 每个向量节点与附近的一些节点建立连接。
2. 上层图更稀疏，充当快速导航“高速路”。
3. 查询从顶层入口开始，逐层贪心地向更近节点移动。
4. 到达底层后，扩大候选搜索范围，选出最终近邻。

HNSW 无需像 IVFFlat 那样先用已有数据训练聚类中心，因此可以在空表或数据逐步增长时建立图索引；代价是构建较慢，索引体积与内存开销通常更大。

### 5.3 HNSW 参数

| 参数 | pgvector 0.8.7 默认值 | 作用 | 调高的代价/收益 |
|---|---:|---|---|
| `m` | 16 | 每个节点连接的邻居数量 | 通常提升图连接能力和召回上限；索引更大、构建更慢 |
| `ef_construction` | 64 | 构建图时的候选列表大小 | 通常改善图质量；构建时间和资源消耗增加 |
| `hnsw.ef_search` | 40 | 查询时动态候选列表大小 | 通常提高 Recall，查询延迟和访问节点数增加 |
| `hnsw.iterative_scan` | off | 被过滤掉后是否继续扫描 | 开启后可缓解过滤后结果不足；可能增加查询工作量 |
| `hnsw.max_scan_tuples` | 20000 | iterative scan 最大访问 Tuple 数 | 上限越高可能找到更多结果，也可能增加延迟 |
| `hnsw.scan_mem_multiplier` | 1 | 相对于 work_mem 的扫描内存倍数 | 提升可用扫描内存，需考虑并发内存预算 |

本项目配置为 HNSW，但当前 `PgVectorStore` 配置没有自定义 `m`、`ef_construction`，因此不能宣称已经调优过这些参数。`ef_search` 是查询时参数，可通过 `SET LOCAL` 单次调整。

### 5.4 HNSW 与 IVFFlat

| 维度 | HNSW | IVFFlat |
|---|---|---|
| 核心结构 | 多层近邻图 | K-means 聚类分区列表 |
| 查询方式 | 从图入口逐层导航 | 找最近聚类中心并扫描若干 List |
| 数据训练 | 无需训练步骤 | 依赖建索引时已有数据训练聚类中心 |
| 构建速度 | 通常较慢 | 通常较快 |
| 内存/索引 | 通常更高 | 通常较低或更易控制 |
| 主要查询参数 | `ef_search` | `probes` |
| 典型风险 | 参数过低或过滤导致漏召回 | 建索引数据太少、probe 太低导致漏召回 |

当前环境和项目配置使用 HNSW；D32 的重点是评估 HNSW 相对精确搜索的质量和速度，不需要同时把 IVFFlat 上生产。

### 5.5 Operator Class 必须匹配查询运算符

向量索引的 Operator Class 决定它支持哪种距离运算：

- `<=>` 必须匹配 `vector_cosine_ops`；
- `<->` 必须匹配 `vector_l2_ops`；
- `<#>` 必须匹配 `vector_ip_ops`。

当前主表索引：

```sql
CREATE INDEX spring_ai_vector_index
ON public.vector_store
USING hnsw (embedding vector_cosine_ops);
```

若用 `<->` 排序却只有 `vector_cosine_ops` 索引，不能期待该 HNSW 索引服务 L2 查询；应看实际 EXPLAIN 计划确认是否退化为全表扫描。pgvector 对 `vector` HNSW 索引还有维度上限 2,000；当前 384 维符合该索引限制。

## 6. 查询计划与实验 SQL

### 6.1 检查环境、维度与索引

```sql
SELECT version();
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';

SELECT table_schema, table_name, column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'vector_store'
ORDER BY ordinal_position;

SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'vector_store'
ORDER BY indexname;

SELECT COUNT(*) AS vector_rows
FROM public.vector_store;
```

如需确认每条向量长度，可读取真实记录执行 `vector_dims(embedding)`；不要仅凭配置文件推断数据库列或实际向量维度。

```sql
SELECT vector_dims(embedding) AS dimensions, COUNT(*)
FROM public.vector_store
GROUP BY vector_dims(embedding);
```

### 6.2 Cosine Distance 与 Similarity 核验

```sql
SELECT
    id,
    metadata::jsonb ->> 'documentId' AS document_id,
    metadata::jsonb ->> 'version' AS document_version,
    embedding <=> CAST(:query_vector AS vector(384)) AS cosine_distance,
    1 - (embedding <=> CAST(:query_vector AS vector(384))) AS cosine_similarity
FROM public.vector_store
ORDER BY embedding <=> CAST(:query_vector AS vector(384)) ASC
LIMIT 10;
```

验证：同一行的 `cosine_similarity = 1 - cosine_distance`；按 `cosine_distance ASC` 得到的顺序，应与按 `cosine_similarity DESC` 得到的顺序一致（注意相同分数的并列项可能顺序不同）。

### 6.3 对比不同 Top-K

使用相同 Query、同一个 384 维 Query Embedding、相同过滤条件，分别执行 `LIMIT 2`、`LIMIT 4`、`LIMIT 8`。记录每条结果的 id、documentId、chunkIndex、distance、similarity 和文本摘要。重点观察：

- 是否遗漏了解决问题的必要步骤；
- 是否有相邻 Chunk 重复信息；
- 是否出现同主题但不适用的其他设备型号或其他文档类型；
- K 增大后新增结果是否仍有可用证据。

### 6.4 对比相似度阈值

```sql
-- 例：阈值 0.72。此 SQL 使用严格大于，和 PgVectorStore 1.1.8 Cosine 路径一致
SELECT id, content,
       1 - (embedding <=> CAST(:query_vector AS vector(384))) AS similarity
FROM public.vector_store
WHERE embedding <=> CAST(:query_vector AS vector(384)) < (1 - 0.72)
ORDER BY embedding <=> CAST(:query_vector AS vector(384)) ASC
LIMIT 5;
```

分别执行阈值 0.50、0.65、0.72、0.80 等值，记录实际条数与命中内容。以上数值仅是实验参数梯度，不是推荐生产阈值。

### 6.5 Metadata Filter

当前可用元数据的过滤样例：

```sql
SELECT id, content,
       embedding <=> CAST(:query_vector AS vector(384)) AS distance
FROM public.vector_store
WHERE metadata::jsonb @@
      '$."documentId" == "DOC-CNC-COOLING-001" && $."version" == "teaching-fixture-2026.11"'::jsonpath
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 5;
```

必须先确认测试数据真的存在这些 Metadata 值。若要验证设备型号或故障码过滤，先把这些字段写入向量 Metadata，再执行过滤，不要用缺失字段的 SQL 结果推断过滤实现无效。

### 6.6 精确搜索与 HNSW 搜索比较

**精确搜索基准：**关闭当前事务中的普通 Index Scan（本例旨在让查询回到全量计算路径），再查询并保存精确 Top-K 的 ID。

```sql
BEGIN;
SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id
FROM public.vector_store
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 10;

SELECT id,
       embedding <=> CAST(:query_vector AS vector(384)) AS distance
FROM public.vector_store
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 10;
COMMIT;
```

**HNSW 路径：**在有足够数据且 planner 认为使用 HNSW 合算时，直接执行相同排序表达式；先用 EXPLAIN 确认是否出现 HNSW Index Scan。

```sql
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id,
       embedding <=> CAST(:query_vector AS vector(384)) AS distance
FROM public.vector_store
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 10;
```

在同一数据集、同一 Query、同一过滤条件、同一 K 下，设精确结果 ID 集合为 `E`、HNSW 结果 ID 集合为 `A`：

\[
ANN\ Recall@K=\frac{|A\cap E|}{|E|}
\]

若精确结果不足 K 条，分母使用精确实际返回数。并记录两种模式的执行时间、Buffers、结果条数和排序差异。需要多条 Query 才能避免单条 Query 偶然性。

**注意：**`EXPLAIN ANALYZE` 会真正执行 SQL。主表当前快照为 0 行时，查询只能验证语法或 Seq Scan 空表计划，不能据此评价 HNSW 性能或召回率。小样本上 planner 不使用 HNSW 也可能是合理选择；不要为了让计划出现 HNSW 而在正式业务中强行关闭 Seq Scan。

### 6.7 过滤导致结果不足与 Iterative Scan

```sql
BEGIN;
SET LOCAL hnsw.ef_search = 100;
SET LOCAL hnsw.iterative_scan = relaxed_order;

EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id,
       embedding <=> CAST(:query_vector AS vector(384)) AS distance
FROM public.vector_store
WHERE metadata::jsonb ->> 'documentId' = 'DOC-CNC-COOLING-001'
ORDER BY embedding <=> CAST(:query_vector AS vector(384))
LIMIT 10;
COMMIT;
```

与默认 `hnsw.iterative_scan=off` 对照，观察返回行数、Recall@10 和延迟。对严格过滤条件，也要比较 planner 是否改用 B-tree 过滤后精确排序；小候选集场景下，精确检索可能更快且 Recall=1。

### 6.8 EXPLAIN ANALYZE 应观察什么

- `Index Scan using spring_ai_vector_index`：确认是否使用预期 HNSW 索引。
- `Order By: (embedding <=> ...)`：确认索引排序运算符是 Cosine Distance。
- `Seq Scan` + `Sort`：可能是精确路径，也可能是索引不匹配或 planner 判断顺序扫描更便宜。
- `Rows Removed by Filter`：观察 Metadata/阈值过滤剔除多少候选。
- `actual rows`：实际返回行数是否达到预期 K。
- `Execution Time`、`Buffers`：不要只看估算 cost；结合多次运行的 p50/p95。

执行 `ANALYZE public.vector_store;` 更新统计信息后再看计划。测试应有足够接近真实分布的数据规模，空表或个位数数据不能证明索引是否有生产价值。

## 7. 检索质量评估

### 7.1 Recall 与 Precision

设某条 Query 的人工标注相关项集合为 R，检索前 K 条为 T：

\[
Recall@K=\frac{|R\cap T|}{|R|}
\]

\[
Precision@K=\frac{|R\cap T|}{K}
\]

Recall 衡量“应该找到的相关内容找到了多少”；Precision 衡量“取回的内容中相关内容占多少”。当实际返回少于 K 条时，应同时报告实际返回条数；指标计算采用固定 K 分母还是实际返回数分母，需在评估规范中明确，不能混用。

人工相关性 Recall/Precision 与 ANN Recall 不同：前者对照人工标注判断内容是否相关；后者对照 Exact Search 判断 HNSW 是否找回真正的最近邻。ANN Recall 高，不代表语义检索结果一定能回答业务问题。

### 7.2 MRR 与 NDCG

- **MRR（Mean Reciprocal Rank）**：对每条 Query，找到第一个相关结果的排名 r，得分为 `1/r`，再对所有 Query 求平均。第一个相关结果越靠前，MRR 越高。
- **NDCG@K（Normalized Discounted Cumulative Gain）**：支持分级相关性，越靠前的高相关结果贡献越大；DCG 经理想排序 IDCG 归一化。适合设备故障检索中“直接解决问题”“提供辅助步骤”“仅主题相关”等分级标注。

常用 DCG 形式：

\[
DCG@K=\sum_{i=1}^{K}\frac{2^{rel_i}-1}{\log_2(i+1)}
\]

\[
NDCG@K=\frac{DCG@K}{IDCG@K}
\]

### 7.3 ANN 召回损失的比较方法

对每条 Query：

1. 使用无 ANN 索引的精确路径获得 Exact Top-K。
2. 使用 HNSW 路径获得 ANN Top-K。
3. 比较两个 ID 集合交集，计算 ANN Recall@K。
4. 记录 HNSW 参数、返回条数、p50/p95 延迟、Buffers 和过滤条件。
5. 多条真实 Query 汇总平均值与低分位表现，重点观察最差 Query，而不只看均值。

如果 HNSW 提速明显但 ANN Recall 明显下降，应提高 `ef_search`、优化索引构建参数或重新评估精确路径是否已足够快。不能只凭“建了 HNSW”就认定查询性能提升。

## 8. 结果去重、无结果与混合检索认知

### 8.1 Chunk 去重与结果多样性

常见问题：

- 固定长度切分产生重叠，邻接 Chunk 内容高度相似；
- 同一文档不同版本可能包含重复文本；
- 同一故障处理步骤在手册和工单规范中重复；
- 头部几条被同一篇文档占据，其他有用来源被挤出 Top-K。

可评估的轻量策略：按 `documentId + version + chunkIndex` 保留唯一项；对同文档相邻 Chunk 做合并或只取最高分项；设置每篇文档最大命中条数；对内容 Hash 相同的重复文本做去重。不要跨文档无条件删除相同文本，因为两个文档可能是独立、有效的证据来源。

当前 `KnowledgeVectorStoreService` 直接把 `similaritySearch()` 返回项映射为结果，没有额外做文档级去重或多样性重排；这属于后续可测量改进点，不是已完成能力。

### 8.2 无结果与低相关结果

- 空结果可能是阈值过高、Metadata 过滤过窄、无数据、向量空间不匹配或检索失败，必须区分原因。
- 少于 K 条可能是符合条件的数据本来就少，也可能是 ANN 过滤候选耗尽。
- 低分结果不应因为“有返回值”就被当成可靠上下文。
- 无可信证据时应拒答、询问设备型号/故障码/运行状态，或建议用户提供维修手册来源；不要强行生成故障原因和处置步骤。

还要把检索到的文档正文视为不可信数据。文档中的指令式文字不能覆盖系统提示或授权规则，检索只提供证据材料，不执行文档内的命令。

### 8.3 Hybrid Search 的定位

向量检索擅长语义相近表达；PostgreSQL 全文检索擅长词项、型号、故障码、零件编号等精确匹配。两者互补：例如“主轴温度报警”与“主轴过热”可能语义接近，而 `AL-0421`、`M8×1` 等代码更适合词项精确检索。后续可评估向量召回与 `tsvector`/GIN 全文检索结果融合，但本 Day 只理解互补价值，不展开 Hybrid Search 实现。

## 9. 本次真实证据与实验记录表

### 9.1 已有事实

- D31 配置文件：384 维、Cosine、HNSW、`public.vector_store`。
- D31 数据库状态快照：PostgreSQL 18.7、pgvector 0.8.7；`public.vector_store` 当时为 0 行；存在 `spring_ai_vector_index` HNSW `vector_cosine_ops` 和主键索引。
- D31 本地 Embedding 证据文件：本地模型样本向量 384 维，样本 L2 Norm 约为 1。
- D31 语料文件包含教学样例：数控机床主轴过热、冷却泵异常、伺服驱动器过载、编码器位置偏差、工单分级等片段。

以上是仓库记录的既有证据，不等于本次重新登录 PostgreSQL 执行了 D32 查询。当前空表状态不允许形成真实 Top-K、Threshold、HNSW 召回率或性能结论。

### 9.2 D32 实验记录模板

| 实验 | Query / 条件 | K / Threshold / Filter | 返回条数 | 首位 Chunk / 内容判断 | 执行计划 / 耗时 | 结论 |
|---|---|---|---:|---|---|---|
| Cosine 排序 | 主轴过热问题 | K=5 | 待实测 | 待人工标注 | 待实测 | 待填写 |
| Top-K 对比 | 同一 Query | K=2/4/8 | 待实测 | 待人工标注 | 待实测 | 待填写 |
| Threshold 对比 | 同一 Query | 0.50/0.65/0.72/0.80 | 待实测 | 待人工标注 | 待实测 | 待填写 |
| Metadata Filter | 文档 ID + 版本 | K=5 | 待实测 | 检查过滤范围 | 待实测 | 待填写 |
| Exact vs HNSW | 同 Query / 同 K | K=10 | 待实测 | ID 集合对比 | 待实测 | 待填写 |
| HNSW Filter Scan | 严格 Metadata Filter | iterative off/on | 待实测 | Recall 与 underfill | 待实测 | 待填写 |

每条实验至少留存：Query 原文、Query Embedding 模型/版本/向量空间、K、阈值、Metadata 条件、完整 SQL、实际结果 ID、距离/分数、执行计划、运行时间、人工相关性标注。禁止补写未运行的结果。

## 10. 最终验收问题：设备故障 Query 的完整检索流程

给定 Query：`数控机床出现主轴过热报警时，先检查什么？`

1. **Embedding**：使用当前向量库相同的 `local-hash-ngram-v1 / 2026.11 / 384 维` 模型对 Query 生成向量；不能更换模型或预处理配置后直接查询旧向量。
2. **Metadata Filter**：如果已知文档范围、版本或租户，先构造服务端可信过滤条件。当前已存在 `documentId`、`version`、`vectorSpaceId` 等元数据；设备型号、故障码、文档类型和租户字段需先补到写入元数据中。
3. **Cosine Distance**：使用 `embedding <=> query_vector` 计算距离。距离越小越接近；若要展示 Cosine Similarity，则计算 `1 - distance`。
4. **Top-K**：按距离 ASC 排序，最多选 K 条候选。当前项目默认 K=4 是代码默认值，需用评估集判断是否合适。
5. **Threshold**：仅保留 Cosine Similarity 严格大于校准阈值的命中。阈值过高可能漏召回，过低会噪声召回；返回不足 K 条是允许的。
6. **可靠性检查**：检查结果是否有实际解决步骤、文档版本是否有效、设备型号是否匹配、是否重复片段，以及是否存在安全操作限制。不能用相似度分数替代事实和权限验证。
7. **HNSW 验证**：使用同一查询和过滤条件分别运行 Exact 与 HNSW 路径，比较 ID 交集得 ANN Recall@K，并记录实际计划、延迟、Buffers 和过滤后行数。过滤较严导致结果不足时，再测试 iterative scans 与 Metadata 索引方案。
8. **无可信命中**：不把弱相关 Chunk 强行塞给 LLM；返回无可靠依据或请求补充设备型号、故障码和运行现象。

最终判断标准不是“拿到了最相似的 K 个向量”，而是：**在权限与业务范围正确的前提下，检索结果是否覆盖了真正相关的证据、排序是否有用、低相关结果是否被过滤、ANN 是否损失可接受、以及查询性能是否经真实数据验证。**

## 11. 官方参考资料

### Spring AI 1.1.8 源码（版本精确）

- Spring AI PgVectorStore.java | Spring AI PgVectorStore 实现源码：[https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java](https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java)
- Spring AI SearchRequest.java | Spring AI 检索请求源码：[https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java)
- Spring AI VectorStoreRetriever.java | Spring AI 向量检索器接口源码：[https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/VectorStoreRetriever.java](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/VectorStoreRetriever.java)
- Spring AI PgVectorFilterExpressionConverter.java | Spring AI PgVector 元数据过滤表达式转换源码：[https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorFilterExpressionConverter.java](https://github.com/spring-projects/spring-ai/blob/v1.1.8/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorFilterExpressionConverter.java)

### PostgreSQL 18 与 pgvector 0.8.7

- pgvector Documentation | pgvector 官方文档：[https://pgvector.app/docs/](https://pgvector.app/docs/)
- HNSW indexes in pgvector | pgvector 中的 HNSW 索引：[https://pgvector.app/hnsw/](https://pgvector.app/hnsw/)
- Filtering and iterative index scans | 过滤条件与迭代式索引扫描：[https://pgvector.app/filtering/](https://pgvector.app/filtering/)
- IVFFlat indexes in pgvector | pgvector 中的 IVFFlat 索引：[https://pgvector.app/ivfflat/](https://pgvector.app/ivfflat/)
- PostgreSQL 18 Documentation: EXPLAIN | PostgreSQL 18 官方文档：EXPLAIN 查询计划：[https://www.postgresql.org/docs/18/sql-explain.html](https://www.postgresql.org/docs/18/sql-explain.html)
- PostgreSQL 18 Documentation: Examining Index Usage | PostgreSQL 18 官方文档：检查索引使用情况：[https://www.postgresql.org/docs/18/indexes-examine.html](https://www.postgresql.org/docs/18/indexes-examine.html)
- PostgreSQL 18 Documentation: jsonb Indexing | PostgreSQL 18 官方文档：jsonb 索引：[https://www.postgresql.org/docs/18/datatype-json.html#JSON-INDEXING](https://www.postgresql.org/docs/18/datatype-json.html#JSON-INDEXING)
- PostgreSQL 18 Documentation: Preferred Index Types for Text Search | PostgreSQL 18 官方文档：全文检索的推荐索引类型：[https://www.postgresql.org/docs/18/textsearch-indexes.html](https://www.postgresql.org/docs/18/textsearch-indexes.html)
- PostgreSQL 18 Documentation: Tables and Indexes | PostgreSQL 18 官方文档：全文检索表与索引：[https://www.postgresql.org/docs/18/textsearch-tables.html](https://www.postgresql.org/docs/18/textsearch-tables.html)

---

> 证据说明：本文的配置和源码判断来自当前仓库 `master` 的 D31 文件；数据库现状引用仓库中 2026-11-14 的只读快照；Spring AI API 与分数转换对照 v1.1.8 源码；pgvector 行为对照 0.8.7 文档。D32 SQL 尚未在本次会话中连接目标数据库执行，所有 `待实测` 项目必须由真实运行补齐，不能当作已验收结果。
