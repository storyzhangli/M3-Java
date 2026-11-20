# D37 骨架实扫记录（执行前置证据）

扫描时间：2026-11-20（本机实测，非任务书转述）

## Git 基线
- HEAD：`27ab844a37001902470ce7124093ccdd8660387e`（27ab844，"D37 项目基础骨架"，2026-11-20）
- 工作区：干净（git status --porcelain 无输出）

## 与任务书的差异（如实记录）
- 任务书写"约 190 个 main Java"，实测 `D37\D37_RAG_ESAB\src\main\java` 共 **251** 个 .java 文件。
- `src\test\java` 为空（无 Java 测试）；已有 `src\test\frontend\front-end-safety.test.js` 与 `src\test\resources\application-test.yml`。
- PG 口令环境变量实测为 `PG_VECTOR_PASSWORD`（application.yml `app.pgvector.password=${PG_VECTOR_PASSWORD:}`；yml 注释中写的 PGVECTOR_DB_PASSWORD 是过时注释，以占位符为准）。

## 技术基线（POM 实读）
- 父 POM：spring-boot-starter-parent 3.5.16；java.version=21；spring-ai.version=1.1.8；mybatis-plus.version=3.5.17。
- 模块：D37\D37_RAG_ESAB（artifactId D37_RAG_ESAB，包 com.zl.d37）。

## CURRENT_RUNTIME_BASELINE（代码真实绑定）
- `app.ai.chunk.chunk-size=500`、`chunk-overlap=100`、min=50、max=1000、length-unit=UTF16_CODE_UNIT。
- `SimilaritySearchRequest`：DEFAULT_TOP_K=5、MIN=1、MAX=20；threshold 默认 0.0（similarity 语义，服务层归一 [0,1]）。
- `app.ai.embedding`：provider=local、model=local-hashing-embedding、model-version=local-v1、dimensions=384、distance=COSINE、max-input-length=8192。
- `app.pgvector`：enabled 默认 true、host 192.168.200.153:5432/spring_ai、table=d37_vector_store、HNSW、COSINE_DISTANCE、initialize-schema=true。
- `app.rag`：budgetUnit=CHARACTER、contextBudgetChars=6000、defaultTopK=5、quotedSnippetMaxChars=200。
- `app.rag.assessment`（EvidenceAssessmentProperties 默认）：lowRelevanceSimilarityThreshold=0.55、minQuestionTermCoverage=0.60、clarifyOnAmbiguousSubject=true、ambiguousSubjectCount=2。
- AI_MODE 默认 mock；deepseek 模式 AI_API_KEY 缺失启动 fail-fast。

## 关键链路事实
- `POST /api/v1/rag/ask` → RagOrchestrator：检索(FAILED 短路) → 组装(无证据拒答) → EvidenceAssessmentService(CONFLICT/REFUSE/CLARIFY 均不调生成) → 仅 ANSWER 调生成 → 响应前批量核验 → 生成后校验。
- `POST /api/v1/retrieval/search`（SimilaritySearchController）→ SimilaritySearchService：显式 Query Embedding + 校验（零向量拒绝）→ VectorStore.similaritySearch（过滤 SQL 下推）→ queryEmbeddingMs/vectorSearchMs/retrievalTotalMs 三段计时（毫秒 3 位小数）。
- 生命周期：DefaultDocumentLifecyclePort 查 MySQL knowledge_document；无行=NOT_FOUND、异常=LOOKUP_FAILED、仅 ACTIVE 有效（fail-closed）。评测前必须确认语料 3 文档在 MySQL 中为 ACTIVE，否则全部 REFUSE。
- Embedding：LocalHashingEmbeddingModel = TextFeatureExtractor（w:/c:/g: 三类特征，toLowerCase，忽略空白）+ FeatureHasher（32 位 FNV-1a，桶=hash%384 负数修正，符号=最高位）+ 带符号累加 + L2 归一化（float 运算）。内存后端可逐位复刻。
- PgVectorStoreConfiguration：`@ConditionalOnProperty(app.pgvector.enabled, matchIfMissing=true)`；**enabled=false 时无 VectorStore Bean，SimilaritySearchService 等无法装配，应用不能启动**；enabled=true 且口令缺失 → PgVectorStoreProperties 校验 fail-fast。启动应用必须注入 PG_VECTOR_PASSWORD。
- 入库入口：`POST /api/v1/vector-index/index`（VectorIndexRequest：documentId/version/strategy/title/chunks[chunkIndex,content,globalStart,globalEnd]，幂等）。
- RagErrorCode 21 值、GenerationStatus、AssessmentDecision(ANSWER/CLARIFY/REFUSE/CONFLICT)、RagSourceView(sourceId=documentId, citationId=sourceRef, url 恒 null) 与任务书一致。

## 语料
- classpath `knowledge/equipment-fault-corpus.json`：3 文档 8 片段，与 D36 完全一致，全部 url=null，含注入教学片段 DOC-SERVO-TROUBLE-002-C03。

## 环境探测（TCP 实测，2s 超时）
| 目标 | 结果 |
|---|---|
| 127.0.0.1:3306 (MySQL) | 可达 |
| 127.0.0.1:6379 (Redis) | 不可达 |
| 192.168.200.153:5432 (PG) | 可达 |
| 127.0.0.1:8080 (应用) | 未启动 |

## 工具链
- Jackson 2.19.0 三件套（databind/core/annotations）本地仓库 `D:\SoftwareInstall\Apache\repository` 已确认存在。
- Maven 3.9.16 / JDK21 沿用统一路径。

## 结论
- 应用启动依赖 PG_VECTOR_PASSWORD（会话注入，不持久化）；无口令时应用无法启动，评测器只能走 IN_MEMORY_LOCAL 独立后端。
- Redis 不可达不影响 RAG 主链路（会话/模板缓存旁路），启动时需观察是否 fail-fast（待启动验证）。
