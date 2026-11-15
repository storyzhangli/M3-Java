# D32 相似度检索与 Top-K 项目迭代实施记录

> 迭代名称：Day 32：相似度检索与 Top-K
> 物理包：`com.zl.d32`；模块：`D32\D32_SSATK`；端口 8080；主类 `com.zl.d32.D32SpringAiApplication`
> 技术基线：JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、PostgreSQL 18.7、pgvector 0.8.7、Embedding 维度 384、COSINE、HNSW
> 检索主链路：`用户问题 → Query Embedding → VectorStore 相似度检索 → Top-K 结果 → 人工相关性评估`

## 1. 需求与仓库现状

### 1.1 任务目标

在 D31 已完成的向量入库、文档关联、版本管理之上，建立可实际使用、可验证的向量相似度检索能力。核心验收是**检索结果真正相关**，而非仅证明接口能返回数据。本次不构建完整 RAG，不引入 Prompt、LLM 生成、Tool Calling、MCP、Agent。

### 1.2 开工前现状（真实取证）

- 本迭代开始时真实知识库为空：PG 向量表与 MySQL 业务表相关数据均为 0 行（已取证）。
- D31 已交付：向量入库/更新/删除/幂等，metadata 真实写入 `documentId、title、version、strategy、chunkIndex、chunkContentHash、splitVersion`；Chunk 主键为 `StableChunkIdGenerator` 产出的 UUID v5。
- D30 已存在 `PgVectorController`（`/api/v1/vector-store`，含 `/documents`、`/search`、`/stats`），其 `VectorSearchRequest` 默认 topK 原为 3。
- 配置事实：`PgVectorStoreConfiguration` 含 `@Primary` 的 MySQL `jdbcTemplate` Bean，避免未限定 JdbcTemplate 被误路由到 PG；PG 表 `public.d32_vector_store` 结构为 `id uuid / content text / metadata json / embedding vector(384)`，主键 + HNSW(vector_cosine_ops)。

### 1.3 关键纠偏：框架 EQ 过滤器真实可用（推翻前假设）

压缩前曾依据字节码字符串常量（`getOperationSymbol` 含 `"=="`）与独立 `SELECT 1 == 1` 报错，推断框架 EQ 过滤器必失败，并据此设计了手写参数化 SQL 双路径。真实运行测试 `frameworkBuiltInEqFilterFailsDueToDoubleEquals` 时**未抛异常**，遂用临时探针 `ConvProbe`（落 `docs\temp_docs\D32`）直接调用 `PgVectorFilterExpressionConverter`，实证真实渲染为 PostgreSQL **SQL/JSON Path** 表达式：

| 条件 | 真实渲染片段 |
|---|---|
| 字符串相等 | `metadata::jsonb @@ '$."documentId" == "d32it-air"'::jsonpath` |
| 整数相等 | `metadata::jsonb @@ '$."version" == 1'::jsonpath` |
| AND | `metadata::jsonb @@ '$."documentId" == "x" && $."version" == 1'::jsonpath` |
| IN | `metadata::jsonb @@ '($."chunkIndex" == 0 || $."chunkIndex" == 1 || $."chunkIndex" == 2)'::jsonpath` |

结论：metadata 列经 `::jsonb` 转换，用 SQL/JSON Path 的 `@@` 匹配运算符在 WHERE 内执行；`==`、`&&`、`||` 均为 **jsonpath 内部运算符**（SQL/JSON 标准路径语言相等用 `==`），不是裸 SQL，故合法。`SELECT 1 == 1` 报错是因为那是裸 SQL。**框架过滤真实可用且真实下推到 PG WHERE，无需手写 SQL。**

## 2. D31 能力复用情况

- 复用 D31 的 `EmbeddingModel`、`VectorStore` Bean、PG 表结构与全部 metadata 字段，不重复实现入库/更新/删除/幂等。
- 入库签名保持不变：`DocumentVectorIndexService.indexChunks(String documentId, Integer version, String strategy, String title, List<Chunk>)`；删除 `deleteByDocument(String)`；列举 `listChunks(String)`。
- 继承 D31 两项修复：`@Insert` 回写自增主键、`@Primary` MySQL jdbcTemplate 路由。
- 未改动 D31、D30 历史快照与无关模块；仅把 D30 `VectorSearchRequest` 默认 topK 由 3 改为 5（任务书要求默认必须为 5）。

## 3. Query Embedding 实现与模型一致性

### 3.1 一致性保证

- 查询与入库使用**同一个** `EmbeddingModel` Bean，不切换模型、模型版本或维度。
- 结果视图回显 `vectorSpaceId = local|local-hashing-embedding|local-v1|384`，供调用方核对查询与入库是否处于同一模型/版本/维度空间。
- 维度固定 384，由 `EmbeddingProperties.dimensions` 驱动，不硬编码。

### 3.2 校验链（fail-closed，不静默截断）

1. 空白查询拒绝（`normalizeQuery`）。
2. 超长拒绝：长度口径为 UTF-16 code unit（字符），与 Embedding 配置一致，**非 Token**；超过 `maxInputLength=8192` 直接报错，不静默截断。
3. 显式调用 `embeddingModel.embed(query)`，调用失败抛 `IllegalArgumentException`（仅记录异常类型，不回显上游细节）。
4. 复用 `VectorValidator.validate`：非空、维度一致、无 NaN/Infinity。
5. 额外拦截全零向量（本地模型对无特征文本会保留零向量）。

## 4. VectorStore 检索实现与 Top-K 行为

### 4.1 统一框架路径

`SimilaritySearchService` 构造器为 4 参：`vectorStore、embeddingModel、embeddingProperties、vectorValidator`。统一调用 `vectorStore.similaritySearch(SearchRequest)`，再把 `Document` 映射为 `SimilarityMatchView`，无第二套手写 SQL 路径。

### 4.2 Top-K 边界

| 常量 | 值 | 说明 |
|---|---|---|
| DEFAULT_TOP_K | 5 | 任务要求默认值 |
| MIN_TOP_K | 1 | 下限 |
| MAX_TOP_K | 20 | 上限，防止一次拉取过多资源 |

服务层对越界值做归一（小于 1 归 1、大于 20 归 20、null 归 5）；Bean Validation 同时在 DTO 层用 `@Min/@Max` 约束。

### 4.3 排序与距离语义

- COSINE 距离由 PG 运算符 `<=>` 计算，按距离升序（相似度降序）返回。
- 阈值字段 `threshold` 为 **similarity 语义**（非 distance），框架内部换算为距离边界 `1 - similarity`；默认 0.0 表示不做额外分数过滤。

### 4.4 端到端流程

```mermaid
graph TD
    subgraph S1 ["入口层"]
        A["用户问题 POST /api/v1/retrieval/search"]
        B ["SimilaritySearchController 参数校验"]
    end
    subgraph S2 ["校验与向量化"]
        C ["空白/超长校验 不静默截断"]
        D ["EmbeddingModel.embed 生成 384 维 Query Embedding"]
        E ["VectorValidator + 全零拦截"]
    end
    subgraph S3 ["检索与下推"]
        F ["buildFilterExpression 构造 Filter.Expression"]
        G ["VectorStore.similaritySearch"]
        H ["PgVectorStore 渲染 jsonpath 并在 PG WHERE 下推"]
    end
    subgraph S4 ["结果映射"]
        I ["Document 映射 SimilarityMatchView"]
        J ["SimilaritySearchResultView 返回"]
    end
    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
```

## 5. 返回结果、来源信息与分数能力

### 5.1 来源字段（全部来自真实 metadata，不臆造）

`SimilarityMatchView` 字段：`chunkId、content、documentId、title、version、strategy、chunkIndex、similarity、distance、metadata`。来源字段均从 metadata 读取，缺失即置 null，不构造不存在的字段。

### 5.2 真实分数语义（区分 similarity 与 distance）

| 字段 | 来源 | 语义 | 方向 |
|---|---|---|---|
| distance | metadata 的 `distance`（PG `<=>` 浮点结果） | 余弦距离 | 越小越相似 |
| similarity | `Document.getScore()`，框架按 `1 - distance` 计算 | 余弦相似度 | 越接近 1 越相似 |

字节码取证（`DocumentRowMapper`）：`ResultSet.getFloat("distance")` 放入 metadata（`DocumentMetadata.DISTANCE`），并以 `1 - distance` 计算 `score`。二者恒满足 `similarity = 1 - distance`，是同一事实的两种表达；**不把 distance 当 similarity，不自行归一化或编造分数**，框架未提供时如实置 null。

## 6. 元数据过滤与版本过滤

### 6.1 过滤字段（真实存在且有业务意义）

仅开放入库时真实写入的 metadata 键：`documentId / title / version / strategy`，均可选；不提供则不过滤。

### 6.2 构造方式（踩编译点已处理）

- `FilterExpressionBuilder.eq/and` 返回内部类型 `Op`（Record），必须 `.build()` 才得到 `Filter.Expression`。
- `and(Op, Op)` 为二元，多条件需嵌套归约；其形参是 `Op`，不能传 `.build()` 后的 Expression。
- 多条件按 AND 归约后 `.build()`，设入 `SearchRequest.Builder.filterExpression`。

### 6.3 真实下推验证

过滤表达式经 `PgVectorFilterExpressionConverter` 渲染为 `metadata::jsonb @@ 'jsonpath'`，拼进 PG SQL 的 WHERE，在数据库侧执行（非内存过滤）。集成测试对过滤前后结果数量、来源与版本进行核对，验证过滤真实生效。

## 7. 十个真实业务问题与评估结果摘要

### 7.1 评估用种子语料（本次自建，非知识库原有）

因起始知识库为空，经真实 D31 入库路径新建 4 文档、24 个 STRUCTURE Chunk（均 version=1）：

| documentId | 文档标题 | Chunk 数 |
|---|---|---|
| d32eval-air | 空压机故障与维修手册 | 6 |
| d32eval-pump | 工业水泵运行与维修手册 | 6 |
| d32eval-code | 设备故障代码对照表 | 6 |
| d32eval-model | 设备型号与工单分类规范 | 6 |

### 7.2 十题与统计（AI 初判，人工复核待完成）

| 题号 | 主题 | R | P | N | Rank1 相关 | 漏召回 |
|---|---|---|---|---|---|---|
| Q1 | 空压机超温处理 | 2 | 1 | 2 | 是 | 未发现 |
| Q2 | 空压机压力不足原因 | 2 | 2 | 1 | 是 | 未发现 |
| Q3 | 水泵异响振动排查 | 1 | 1 | 3 | 是 | **1 项** |
| Q4 | 水泵密封漏水处理 | 2 | 2 | 1 | 是 | 未发现 |
| Q5 | 代码 E-101 含义 | 1 | 0 | 4 | 是 | 未发现 |
| Q6 | 代码 E-205 含义 | 1 | 0 | 4 | 是 | 未发现 |
| Q7 | 型号 AC-380 | 1 | 1 | 3 | 是 | 未发现 |
| Q8 | 型号 WP-75 | 1 | 1 | 3 | 是 | 未发现 |
| Q9 | 电机不启动工单分类 | 2 | 3 | 0 | 是 | 未发现 |
| Q10 | 空压机保养项目周期 | 1 | 1 | 3 | 是 | 未发现 |
| 合计 | — | 14 | 12 | 24 | 10/10 | 1 |

- 严格相关率 R/50 = 14/50 = 28%；有帮助率 (R+P)/50 = 26/50 = 52%。
- **Rank1 命中率 10/10 = 100%**：每题最相关片段均排第一。
- 完整逐题明细（含全 chunkId、分数、内容摘要、AI 理由与人工复核空列）见 `docs\evidence\D32\D32-十题相关性评估记录.md`。

## 8. 噪声、漏召回与分数分布

### 8.1 漏召回（仅 1 项，有真实目标可核对）

Q3 漏 `d32eval-pump` chunk4（Chunk ID `0533ec49-ca47-59fd-9e9b-42b0611fd5ee`），其内容为气蚀「噪声、振动与流量下降」，与异响振动主题相关但用词不同（异响→噪声、术语「气蚀」查询未含）。本地词面模型无法桥接同义词/术语差异，被排到 Top-5 外。其余各题经核对当前 24 Chunk 未发现漏召回。

### 8.2 分数分布（无干净阈值）

| 类别 | 观测相似度区间 |
|---|---|
| 相关 R | 约 0.3166 – 0.5379 |
| 部分相关 P | 约 0.1546 – 0.4070 |
| 不相关 N | 约 0.1124 – 0.4103 |

- 高分噪声：Q6 Rank2 code/5（E-302）0.4103、Q5 Rank2 code/4（E-301）0.3971，均不相关。
- 低分相关：Q10 Rank1 air/5 0.3166、Q3 Rank1 pump/0 0.3198。
- N 与 R 在 0.32–0.41 区间明显重叠，单一全局阈值会同时保留高分噪声或误杀低分相关；跨题分数绝对值不可直接比较。

### 8.3 阈值结论

本阶段**不设通用阈值**，默认 `similarityThreshold=0.0`，先依赖排序 + 人工复核；是否需要阈值留待更大规模、完成人工复核的数据上再讨论。

## 9. 代码变更清单

| 类型 | 文件 | 用途 |
|---|---|---|
| 新增 | `retrieval\dto\SimilaritySearchRequest.java` | 检索入参，默认 Top-5、边界 1..20 |
| 新增 | `retrieval\dto\SimilarityMatchView.java` | 单条命中视图，含真实分数与来源 |
| 新增 | `retrieval\dto\SimilaritySearchResultView.java` | 结果视图，回显口径与向量空间 |
| 新增 | `retrieval\SimilaritySearchService.java` | 检索应用服务，统一框架路径 + buildFilterExpression |
| 新增 | `retrieval\SimilaritySearchController.java` | POST `/api/v1/retrieval/search` |
| 修改 | `vectorstore\dto\VectorSearchRequest.java` | D30 默认 topK 3→5 |
| 删除 | `retrieval\FilteredSimilaritySearcher.java` | 手写 SQL 双路径，经纠偏后废弃删除 |
| 新增测试 | `SimilaritySearchServiceTest`（11）、`SimilaritySearchControllerTest`（4）、`SimilaritySearchIntegrationTest`（5） | 单元/控制器/真实集成测试 |
| 新增门控 | `support\EnabledIfPgVector.java`、`PgVectorReachableCondition.java` | PG 可达门控 |
| 新增工具 | `evaluation\RetrievalEvaluationTool.java` | 采集工具（类名无 Test 后缀，不被默认 surefire 拾取） |

## 10. 测试结果与实际操作记录

### 10.1 构建命令（真实执行）

```powershell
$env:PGVECTOR_DB_PASSWORD='********（环境变量注入）'
& mvn.cmd -f "E:\AI\project\AI-Learning-one\M3-Java\D32\D32_SSATK\pom.xml" clean test
```

### 10.2 真实结果

- 结果：`Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`，**BUILD SUCCESS**，总耗时约 29.3 秒。
- 分测试类：ServiceTest 11、ControllerTest 4、IntegrationTest 5（后两者连真实 PG/MySQL，含种子入库与 tearDown 清理）。
- 日志：`logs\D32\full-test-01.txt`。
- 说明：surefire 存在一个 `.dumpstream`，内容为 Boot Manifest JAR 绝对路径 hint 与 'other has different root'，非崩溃、不影响结果。

## 11. 数据清理方式与安全合规

- 评估语料（4 docId、共 24 行）当前**真实保留**在 PG 供人工复核，未擅自删除。
- 复核后清理方式：对 4 个 docId 分别调用 `DocumentVectorIndexService.deleteByDocument(docId)`，或经评估工具幂等删除；执行前需用户确认。
- 密码全程通过环境变量注入，未写入 Git/文档/证据/日志；本记录一律以 `********` 或「环境变量注入」表述。
- 未执行任何 git commit/push/reset/clean，未做 docker prune、删卷或重建容器，未引入第二套向量库。

## 12. 记忆简语与层级树

记忆简语：**同模型、同空间、三八四；距离小、相似度高、Top 五；jsonpath 真下推、不写裸 SQL；分数不编造、阈值不拍脑袋；AI 初判、人工复核。**

层级树：

```text
D32 相似度检索
├─ 入口 Controller (/api/v1/retrieval/search)
│  └─ 入参 Request (query/topK/threshold/过滤)
├─ 服务 Service
│  ├─ 校验 (空白/超长/全零)
│  ├─ Query Embedding (384, 同模型)
│  ├─ 过滤表达式 (Filter.Expression → jsonpath)
│  └─ 框架检索 (VectorStore.similaritySearch)
├─ 视图 View
│  ├─ 来源 (documentId/title/version/strategy/chunkIndex)
│  └─ 分数 (distance / similarity)
└─ 评估
   ├─ 24 种子 Chunk / 10 题 / 50 命中
   ├─ R14 P12 N24, Rank1 100%
   └─ 漏召回 1 (Q3 气蚀)
```

## 13. 未完成事项

1. **人工相关性复核未完成**：50 条 `□ 待确认` 需人工逐条确认或修正，重点复核 Q3 漏召回。
2. 因人工标注未完成，本迭代**不报 Level 5**，最终等级见交付汇报。
3. 遗留待拍板（D31 沿用）：MySQL 旧表 `knowledge_document/page` 与当前 schema 冲突（0 行），DROP/ALTER/不动三选项未决，确认前不做破坏性操作。
