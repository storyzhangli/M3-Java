# D39-R01 基线复核记录（#232，2026-11-22）

- HEAD=da180ef（D39 项目基础骨架），工作区干净（git status --porcelain 空）
- 前序：3947830（D39 知识笔记）→ ccd7f9f（D38 R02）→ 0652b25（D38 R01）
- 模块：D39/D39_KAAMF，包 com.zl.d39，main Java=294（与任务书 §1.1 实测一致）
- access 包不存在（Test-Path False）；src/test 仅 front-end-safety.test.js + application-test.yml（无 Java 单测，一致）

## 精读确认的关键契约（与任务书一致）

1. SimilaritySearchService.search：normalizeQuery→validateLength(UTF-16)→embedAndValidate(零向量拒)→SearchRequest.builder+FilterExpressionBuilder(documentId/title/version/strategy eq AND)→buildFilterExpression 返回 Filter.Expression（private，enabled=false 路径零改动可行）。
2. KeywordFilterCriteria：4 字段 record + ChunkFields 4 字段（禁 Map，先过滤后排名）；matches() AND 语义、isBlank=不限制。
3. InvertedIndex：build() SELECT id::text,content,metadata::text FROM public.d39_vector_store ORDER BY id；canonical 行=`chunkId|contentSha|chars|documentId|version|strategy|chunkIndex|splitVersion`（**不含权限键**：P2 给既有行 metadata 追加键不影响指纹；夹具新增行必然改指纹→D5 流程必须）；指纹不等抛 CORPUS_DRIFT；空表抛 KEYWORD_INDEX_EMPTY；Posting(chunkId,tf)；ChunkEntry 11 字段。
4. HybridSearchService：串行向量→关键词→RRF；向量路复用 SimilaritySearchService（过滤注入后自动继承）；criteria 构造点 line 67-68（权限并入口）；KEYWORD_ONLY similarity=null 不伪造。
5. ChatController.resolveOwner(X-Dev-User header)（line 80-81）；HistoryProperties 注释明示"非生产鉴权"；ChatHistoryService.requireOwnedSession 同异常不泄露存在性（owner 校验语义可复用，改动点=owner 来源）。
6. Result：code/msg/data；ResultCode：200/4001/5000/5001/5002；GlobalExceptionHandler 包装 HTTP200+业务码（D38 先例）。
7. DocumentVectorIndexService.buildMetadata（481-501）：documentId/title/version/strategy/chunkIndex/chunkContentHash/splitVersion(+page/lastPage/paragraphIndex/paragraphIndices)——无权限键，追加点在 splitVersion 之后按"有值才写"。
8. application.yml：d39_vector_store、initialize-schema=true（教训 §3.7 项）；hybrid expected-collection-fingerprint=10fc69d5…（与 D38 同值，语料同源假设，KP2 实测）；access 段不存在（P3 追加）。

## 端点账目

- 实测 Mapping 注解 68 行 = 18 类级前缀 + **50 方法级端点**（endpoint-raw.txt）；任务书"66"为编制时口径，差异按溯源纪律记录，执行以实扫 50 为准（分级见 endpoint-inventory.csv，无遗漏）。

## 待办移交 #233

- KP2：行数/metadata 键集合/knowledge_document 列/指纹重算 harness（d39_probe）
- KP3：FilterExpressionBuilder 表达面 javap + jsonb 缺失键行为学实证（应用端点：eq tenantId 对遗留行零命中）
- KP4：夹具文本设计冻结（D2P-771 与查询词面一致的构造法）
