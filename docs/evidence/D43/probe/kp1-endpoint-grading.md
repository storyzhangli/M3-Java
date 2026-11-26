# KP1 主链盘点与六域映射（D43-R01 冻结版）

- 冻结时间：2026-11-26（探测证据=本目录源码实读，行号以 HEAD c47be85 工作区为准）
- 端点总数：**57**（20 控制器，@*Mapping 注解全量枚举）；读写分级 W=24 / R=33
- 门禁两模型：①Filter 层（AuthContextFilter，access.enabled=true 时非 publicPaths 全等认证，匿名=4030）②端点/服务层授权（guardAdmin/checkReadable/requireDocument/service 预门禁/owner 服务端解析）

## 1. 端点分级表（权限口径列=enabled 态实际生效授权；disabled 态=零差异全放行）

| 控制器 | 端点 | 读写 | 授权口径 | 证据位置 |
|---|---|---|---|---|
| ChatController | POST /api/v1/ai/chat | W | OWNER-SRV | ChatHistoryService.resolveOwner L90–95（enabled 只取 AuthContextHolder.current）|
| ChatController | GET /api/v1/ai/health | R | AUTH-ONLY | 不在 publicPaths（=["/error"]）→enabled 匿名 4030 |
| ChatStreamController | POST /api/v1/ai/chat/stream | W | OWNER-SRV | L99 resolveOwner 同链 |
| DeviceChatController | POST /api/v1/device/chat | W | OWNER-SRV | DeviceChatController L49–50 |
| HistoryController | GET /api/v1/ai/sessions | R | OWNER-SRV | L42–43 pageSessions(owner) |
| HistoryController | GET /api/v1/ai/sessions/{id}/messages | R | OWNER-SRV | L59–61 |
| DocumentController | POST /register, /import | W | ADMIN+Aaudit | L117–131 guardAdmin（D40 收口）|
| DocumentController | GET /{documentId} | R | **GAP-META**（无 checkReadable） | L133–136 |
| DocumentController | GET / (list) | R | **GAP-META**（无过滤） | L138–143 |
| DocumentController | GET /{id}/pages, /pages/{n}, /chunks, /chunks/comparison | R | DOC-SCOPE | L146–190 checkReadable |
| EmbeddingController | POST /documents/{id}/embeddings | W | DOC-SCOPE | L70–76 |
| EmbeddingController | GET /embeddings/model | R | AUTH-ONLY | 只读模型元数据 |
| EmbeddingController | POST /embeddings/index/compatibility | W* | AUTH-ONLY | 服务无 INSERT/SAVE 实证=dry-run 探测（W* 伪写面） |
| SimilaritySearchController | POST /retrieval/search | W(查询) | SERVICE-PRE | SimilaritySearchService L101 andWithAccess(requireCurrent) |
| DxxSyncController | GET /sync/status | R | ADMIN | L98 guardAdmin（粗扫漏判修正）|
| DxxSyncController | POST /retry, /consistency | W | ADMIN+Audit | L107+/consistency |
| TableController | GET tables / rows / query（3 读） | R | DOC-SCOPE | guardRead L109/128/142（rows 先定位归属文档再放行 L124–128）|
| TableController | POST /{id}/tables/extract | W | ADMIN+Audit | L161 |
| VectorIndexController | POST index/indexFromPages/reconcile/recover, DELETE /{id} | W | ADMIN+Audit | guardAdmin L104–151（D41 收口）|
| VectorIndexController | GET status/chunks | R | DOC-SCOPE | checkReadable L159/168 |
| PgVectorController | POST documents/search, GET stats | W/R | ADMIN | L53/62/71 requireAdmin |
| HybridKeywordIndexController | POST /rebuild | W | ADMIN | L49 |
| HybridKeywordIndexController | GET /stats | R | AUTH-ONLY | 无端点级门禁 |
| HybridRagController | POST /hybrid/rag/ask | W(查询) | SERVICE-PRE | HybridSearchService L212 关键词路权限代数 |
| HybridSearchController | POST /hybrid/retrieval/search | W(查询) | SERVICE-PRE | 同上 |
| OneEmbeddingController | GET usage/model | R | AUTH-ONLY | 控制器无门禁（轨默认关）|
| OneEmbeddingController | POST index/compatibility | W* | AUTH-ONLY | dry-run 语义 |
| OneRagController | POST /one/rag/ask | W(查询) | SERVICE-PRE | OneRagRetrievalService L45 requireCurrent（try 外）|
| OneSimilaritySearchController | POST /one/retrieval/search | W(查询) | SERVICE-PRE | OneSimilaritySearchService L94 |
| OneVectorIndexController | POST index/reconcile, DELETE /{id} | W | **GAP-ADMIN** | 控制器零门禁（对照 one_ 检索族已收口=不对称）|
| OneVectorIndexController | GET status/chunks | R | **GAP-DOC** | 同上 |
| PromptTemplateController | GET prompts / prompts/{id} / roles | R | AUTH-ONLY | 服务 require*=requireActive 有效性非权限（L218）|
| PromptTemplateController | POST prompts / PUT /{id} / PATCH status / DELETE | W | **GAP-ADMIN** | 零鉴权零审计零所有权；X-Dev-User 头可伪造全局资产 |
| RagController | POST /rag/ask | W(查询) | SERVICE-PRE | RagRetrievalService L52 try 外预门禁 |

## 2. 缺口登记与裁决（KP1 裁决=是否阻断；P2 执行修复）

| # | 缺口 | 性质 | 裁决 | 依据 |
|---|---|---|---|---|
| G2 | PromptTemplate **4 写面**无 ADMIN/审计，全局共享资产，enabled 态 USER 可伪造头删改 | **阻断（H7 未鉴权/无授权写面必修）** | FIX-D43-002 | H7 条款原文；D41/D40 guardAdmin 对称模板=单文件最小修复 |
| G4 | OneVectorIndex **3 写面**无 ADMIN（主轨同面 D41 已收口，one_ 检索族已收口=不对称） | **阻断（H7）** | FIX-D43-003（双开关默认关=触发条件受限，仍修） | 同上 |
| G1 | Document detail/list 读面无 checkReadable（元数据 title/source/status，不含正文/页/chunk） | 残留（信息暴露型，非写面非本体泄露） | 记录不修，D44 候选 | D39_state L50 同名遗留延续；H7 必修条款限于写面；矩阵如实标 "存在性泄露（元数据）" |
| G3 | Embedding/OneEmbedding compatibility POST=写面形态读语义（服务零写库实证）；model/usage/health/stats=只读元数据 | 记录 | 矩阵预期=AUTH-ONLY，不构成缺口 | VectorIndexCompatibilityService 无 INSERT/SAVE |
| G5 | DeviceChat/Chat/ChatStream/History owner 解析：enabled=服务端；disabled=X-Dev-User 头信任 | 既定设计（D39 P3 裁决双态） | 零差异 | ChatHistoryService L91–95 注释+实现 |

## 3. 六域主链映射（§3.1 冻结）

| 域 | 组件（实测类名） | 端点锚 |
|---|---|---|
| PARSE | KnowledgeDocumentParser/PdfDocumentParser/DocumentImportService/table 分支（D41 PdfTableExtractor） | POST /documents/import、GET pages、tables 族 |
| CHUNK | TextCleaner/ChunkStrategy/DocumentChunkService | GET /{id}/chunks、chunks/comparison |
| EMBED | local-hashing 384/ChunkEmbeddingService/VectorIndexCompatibilityService | POST embeddings、vector-index/index |
| RETRIEVAL | SimilaritySearchService/RagRetrievalService/hybrid/one_ 族 | retrieval/search、rag/ask、hybrid 2、one 2 |
| GENERATION | GenerationService(mock)/RagPromptBuilder/引用核验链 | rag/ask 生成段、citation |
| ACCESS | AccessPolicyService/AuthContextFilter/AdminAuditService | 全端点（分级表 §1） |

主域裁决规则（冻结）：权限>解析>切分>Embedding>检索>生成；多标签全量保留；无证据=UNATTRIBUTED。
