# D36 基线扫描证据（00-baseline-scan）

> 生成时间：2026-11-19；扫描方式：本地 PowerShell 实扫 + JDBC 只读探测（DbProbe.java / StaleProbe.java，存于 docs/temp_docs/D36）。
> 本文件全部为已核实事实；未核实项显式标注。

## 1. 仓库与技术基线

- 仓库根：`E:\AI\project\AI-Learning-one\M3-Java`；git HEAD `7fecbde`（"D36 项目基础骨架"），扫描时工作区干净。
- 模块：`D36\D36_SPCE`，包 `com.zl.d36`，251 个 Java 文件；JDK 21.0.7、Spring Boot 3.5.16、Spring AI 1.1.8（BOM）。
- Maven：`D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`，本地仓库 `D:\SoftwareInstall\Apache\repository`。

## 2. 双基线

### CURRENT_RUNTIME_BASELINE（代码真实绑定，已核实）

| 项 | 真实值 | 出处 |
|---|---|---|
| chunkSize | 500（UTF-16 code unit） | ChunkProperties + application.yml `${CHUNK_SIZE:500}` |
| chunkOverlap | 100 | ChunkProperties + `${CHUNK_OVERLAP:100}` |
| 检索 topK 默认 | 5（范围 1..20） | SimilaritySearchRequest.DEFAULT_TOP_K |
| 检索 threshold 默认 | 0.0（不过滤，similarity 方向） | SimilaritySearchService.normalizeThreshold |
| /index 默认策略 | STRUCTURE | VectorIndexController（本实验显式传 FIXED_SIZE） |
| Embedding | local / local-hashing-embedding / local-v1 / 384 维 / COSINE | EmbeddingProperties + yml |
| 运行模式 | mock（AI_MODE 未设置；检索链路用本地 Embedding，不触发 DeepSeek） | yml `${AI_MODE:mock}` |

### G0_CONTROLLED_BASELINE（需求指定受控基准，非仓库默认）

chunkSize=200、overlap=20、topK=5、threshold=0.25、strategy=FIXED_SIZE。

## 3. 实验通道与隔离机制（已核实代码）

- 检索：`POST /api/v1/retrieval/search`（SimilaritySearchService → VectorStore.similaritySearch，阈值/过滤 SQL 下推）。
- 入库：txt/md 导入（BasicDocumentParser）→ `POST /api/v1/vector-index/documents/{id}/index?strategy=FIXED_SIZE` → DocumentChunkService.runStrategy → FixedSizeSplitter（读 ChunkProperties.chunkSize/chunkOverlap）→ Embedding → PgVectorStore。
- **表级隔离**：PgVectorStoreService.qualifiedTable() = schema.table，表名由 `${PGVECTOR_TABLE:d36_vector_store}` 注入；读写同表。实验组各用独立表：`d36_exp_g0` / `d36_exp_g1` / `d36_exp_g2`（initialize-schema=true 自动建表，无删表行为）。
- vectorSpaceId 由 provider+model+version+dimensions 构成，不含切分参数 → 必须靠表名隔离（已采纳）。
- 幂等：indexChunks 按 payloadHash+chunk 数短路；切分配置不同 → hash 不同 → 真实重建。
- 导入按内容 SHA-256 去重：同一语料多组共用同一 MySQL 文档与页，各组仅 PG 表不同（符合"同一文档集"固定项）。

## 4. 数据库现状（JDBC 只读实测）

- MySQL 127.0.0.1:3306/ai_db 可达；`knowledge_document`=0 行、`knowledge_document_page`=0 行（D35 已清理）；残留 `document_chunk_index`=24 行、`document_vector_index`=4 行，全部属 D32 的 `d32eval-*` 文档，与本实验新 documentId 无冲突，不触碰。
- PG 192.168.200.153:5432/spring_ai 可达，pgvector **0.8.7**；已有 d30–d36 各表；`d36_vector_store`=0 行（保持不动）；实验新建 d36_exp_g0/g1/g2。
- Redis 127.0.0.1:6379 可达。
- 口令：PG 经会话级桥接 `PGVECTOR_DB_PASSWORD ← PG_VECTOR_PASSWORD`（不持久化、不落盘）；MySQL 用本机默认；全程无真实 DeepSeek 调用（检索实验不需要生成）。

## 5. 语料（已通读全文）

`resources/knowledge/equipment-fault-corpus.json`：3 文档 8 证据片段——COOLING-001（C01 巡检/C02 温度传感器/C03 冷却泵）、SERVO-002（C01 过载/C02 编码器/C03 注入防护教学）、WORKORDER-003（C01 分级/C02 关单）。片段长约 60–75 字符。

## 6. 耗时测量缺口与最小增量决策

- 现状：检索响应不含分段耗时（nanoTime 仅在日志切面）。
- 决策：对 SimilaritySearchService/ResultView 做**只增不改**的最小增量——新增 queryEmbeddingMs / vectorSearchMs / retrievalTotalMs 三个可空字段与服务内计时，不改变既有字段与语义；Runner 另记客户端总耗时交叉核对。

## 7. 实验组执行拓扑

| 组 | 变量 | chunkSize/overlap | topK/threshold | PG 表 | 启动 |
|---|---|---|---|---|---|
| G0 | 受控基准 | 200/20 | 5/0.25 | d36_exp_g0 | Boot1 |
| G3 | 只改 Top-K | 200/20 | 10/0.25 | d36_exp_g0 | Boot1 复用 |
| G4 | 只改阈值 | 200/20 | 5/0.35 | d36_exp_g0 | Boot1 复用 |
| G1 | 只改 chunkSize | 500/20 | 5/0.25 | d36_exp_g1 | Boot2 |
| G2 | 只改 overlap | 200/50 | 5/0.25 | d36_exp_g2 | Boot3 |

预热 3 次不纳入统计；正式 3 轮；固定问题顺序。