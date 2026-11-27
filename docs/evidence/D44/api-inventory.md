# D44 API 清单（答辩四件套·API 说明 | 源码实扫权威版）

> 采集：`@*Mapping` 注解源码全量扫描（api-detail-scan.txt）+ KP1 反射盘点互证（57=57 恒等）。
> 权限分级：guardAdmin/requireAdmin 方法级实扫=**22 ADMIN**，其余 35 PUBLIC（disabled 门禁整体跳过=H7；enabled 下 ANON=4030）。
> 端口：8080（D44_RAG_PDAM）。全局包络：`Result{code,msg,data}`；错误码见 §4。

## 1. 主链 RAG 域（读面）

| # | 方法 | 路径 | 权限 | 契约摘要 |
|---|---|---|---|---|
| E00 | POST | /api/v1/rag/ask | PUBLIC | RAG 问答主编排（检索→证据判定→生成/拒答）；resp：decision(ANSWER/REFUSE/CLARIFY)、answer、sources[]（含 citation 三码审计位）、retrievalStatus、retrievedCount/evidenceCount |
| E01 | POST | /api/v1/retrieval/search | PUBLIC | 向量检索；req：query, topK(默认5), minSimilarity?；resp：matches[]{chunkId,documentId,title,version,strategy,chunkIndex,content,similarity,distance,metadata,page/lastPage,paragraphIndex} |
| E02 | POST | /api/v1/hybrid/rag/ask | PUBLIC | 混合检索轨问答（D38，enabled 依赖） |
| E03 | POST | /api/v1/hybrid/retrieval/search | PUBLIC | 混合检索（vector+keyword RRF） |
| E04 | GET | /api/v1/hybrid/keyword-index/stats | PUBLIC | 倒排索引统计 |
| E05 | POST | /api/v1/hybrid/keyword-index/rebuild | **ADMIN** | 倒排重建（指纹注入 HYBRID_EXPECTED_FINGERPRINT，D44 新锚 de8d2f0c） |

## 2. 文档/向量索引域

| # | 方法 | 路径 | 权限 | 契约摘要 |
|---|---|---|---|---|
| E06 | POST | /api/v1/documents/register | **ADMIN** | 登记（documentId 唯一；重复=4001"文档不存在/已存在"族） |
| E07 | POST | /api/v1/documents/import | **ADMIN** | multipart 导入（file, documentId, source, title；解析+切分+表格钩子；**先 register 后 import**） |
| E08 | GET | /api/v1/documents | PUBLIC | 列表（本轮 18；视图无 lifecycleStatus 字段=HTTP 非权威，DB 直查） |
| E09 | GET | /api/v1/documents/{documentId} | PUBLIC | 单文档元信息 |
| E10 | GET | /api/v1/documents/{documentId}/pages | PUBLIC | 分页列表 |
| E11 | GET | /api/v1/documents/{documentId}/pages/{pageNumber} | PUBLIC | 单页正文 |
| E12 | GET | /api/v1/documents/{documentId}/chunks | PUBLIC | 片段列表 |
| E13 | GET | /api/v1/documents/{documentId}/chunks/comparison | PUBLIC | 版本片段对照 |
| — | ~~DELETE~~ | /api/v1/documents/{documentId} | **不存在（405→5000 包装）** | **OBS-D44-X3**：文档删除唯一通道=vector-index 域 E17 |
| E14 | POST | /api/v1/vector-index/index | **ADMIN** | 索引写入（payload：documentId,version,strategy,title,chunks[]；同事务替换旧版本） |
| E15 | POST | /api/v1/vector-index/documents/{documentId}/index | **ADMIN** | 从已存页重建索引（indexFromPages） |
| E16 | POST | /api/v1/vector-index/documents/{documentId}/index | **ADMIN** | 同上别名（路径实扫双挂点，见 api-detail-scan） |
| E17 | DELETE | /api/v1/vector-index/documents/{documentId} | **ADMIN** | 删除闭环四步：标记→PG 清→chunk/表格域清→sync DELETE-SYNCED；**不含登记行（OBS-D44-X4）**；重复删除幂等 |
| E18 | POST | /api/v1/vector-index/reconcile | **ADMIN** | 三轨对账；req：{dryRun?,documentId?,prefix?}；resp：pgTotalRows/orphanVectors/missingVectors/syncStuck/tableOrphans/countMismatches |
| E19 | POST | /api/v1/vector-index/recover | **ADMIN** | 对账修复执行（非 dryRun 联动） |
| E20 | GET | /api/v1/vector-index/documents/{documentId}/status | PUBLIC | 索引状态机读面 |
| E21 | GET | /api/v1/vector-index/documents/{documentId}/chunks | PUBLIC | 账本片段读面（删除后=0 验证点） |

## 3. 治理/同步/审计域

| # | 方法 | 路径 | 权限 | 契约摘要 |
|---|---|---|---|---|
| E22 | GET | /api/v1/sync/status | **ADMIN** | 全量/按 documentId 查同步事件行 |
| E23 | POST | /api/v1/sync/retry | **ADMIN** | 手动重试（RETRY_EXHAUSTED 行 attempt 重置） |
| E24 | POST | /api/v1/sync/consistency | **ADMIN** | 一致性核查扩展 |
| E25 | GET | /api/v1/documents/{documentId}/tables | PUBLIC | 表格记录列表（D41 遗留域视图=0，隔离正确） |
| E26 | GET | /api/v1/tables/{tableId}/rows | PUBLIC | 逐格数据行（cells_json 类型化） |
| E27 | GET | /api/v1/documents/{documentId}/tables/query | PUBLIC | 条件查询 |
| E28 | POST | /api/v1/documents/{documentId}/tables/extract | **ADMIN** | 手动重抽取 |
| E29 | POST | /api/v1/vector-store/documents | **ADMIN** | PG 直写（低层通道） |
| E30 | POST | /api/v1/vector-store/search | **ADMIN** | PG 直查（低层通道） |
| E31 | GET | /api/v1/vector-store/stats | **ADMIN** | PG 统计 |

## 4. 会话/模板/AI 域

| # | 方法 | 路径 | 权限 | 契约摘要 |
|---|---|---|---|---|
| E32 | POST | /api/v1/ai/chat | PUBLIC | 对话；**既有会话追加语义**——自定义 session 不存在=4001；省略 sid 自动建会话（OBS-D44-X2 坑②）；scene 枚举 8 值（'SERVICE' 非法=坑①） |
| E33 | POST | /api/v1/ai/chat/stream | PUBLIC | SSE 流式（`@PostMapping(value=,produces=text/event-stream)`——注解 value= 形态，简单正则易漏） |
| E34 | GET | /api/v1/ai/health | PUBLIC | 健康+模式回显 |
| E35 | GET | /api/v1/ai/sessions | PUBLIC | 会话列表（owner 维度） |
| E36 | GET | /api/v1/ai/sessions/{sessionId}/messages | PUBLIC | 消息列表 |
| E37 | GET | /api/v1/ai/prompts | **ADMIN** | 模板列表 |
| E38 | GET | /api/v1/ai/prompts/{templateId} | **ADMIN** | 模板详情 |
| E39 | POST | /api/v1/ai/prompts | **ADMIN** | 新建 |
| E40 | PUT | /api/v1/ai/prompts/{templateId} | **ADMIN** | 更新（版本+1） |
| E41 | PATCH | /api/v1/ai/prompts/{templateId}/status | **ADMIN** | 启停 |
| E42 | DELETE | /api/v1/ai/prompts/{templateId} | **ADMIN** | 软删（deleted 列） |
| E43 | GET | /api/v1/ai/roles | **ADMIN** | 角色枚举 |
| E44 | GET | /api/v1/embeddings/model | PUBLIC | embedding 模型信息 |
| E45 | POST | /api/v1/documents/{documentId}/embeddings | PUBLIC | 单文档向量化触发 |
| E46 | POST | /api/v1/embeddings/index/compatibility | PUBLIC | 索引兼容性自检 |
| E47 | POST | /api/v1/device/chat | PUBLIC | 设备域对话 |

## 5. 百炼隔离轨（one_ 域，D38 契约）

| # | 方法 | 路径 | 权限 | 契约摘要 |
|---|---|---|---|---|
| E48 | GET | /api/v1/one/embeddings/usage | PUBLIC | 用量 |
| E49 | GET | /api/v1/one/embeddings/model | PUBLIC | 模型 |
| E50 | POST | /api/v1/one/embeddings/index/compatibility | PUBLIC | 兼容性 |
| E51 | POST | /api/v1/one/rag/ask | PUBLIC | 隔离轨问答 |
| E52 | POST | /api/v1/one/retrieval/search | PUBLIC | 隔离轨检索 |
| E53 | POST | /api/v1/one/vector-index/index | **ADMIN** | 隔离轨入库（one_d44_vector_store） |
| E54 | DELETE | /api/v1/one/vector-index/documents/{documentId} | **ADMIN** | 隔离轨删除闭环 |
| E55 | GET | /api/v1/one/vector-index/documents/{documentId}/status · chunks | PUBLIC | 隔离轨读面×2 |
| E56 | POST | /api/v1/one/vector-index/reconcile | **ADMIN** | 隔离轨对账 |

> 计数口径：E 编号按域聚合展示，逐行明细（57 条方法级记录）= `probe\api-detail-scan.txt`；与 KP1 反射盘点 20 控制器×57 端点恒等。ADMIN 22 / PUBLIC 35。

## 6. 错误码语义（实测集合，源码为准）

| code | 语义 | 触发例 |
|---|---|---|
| 200 | 成功 | — |
| 4001 | 参数/业务前置校验拒绝 | 文档未注册即 import、会话不存在、重复 register |
| 4030 | 门禁拒绝（**无/非法/过期 token 统一码**） | enabled 下 ANON 全端点=4030（A6 探针 7/7）；过期 token 与非法 token 同码同文案 |
| 405→5000 | 路由缺失被 GlobalExceptionHandler 包装 | DELETE /api/v1/documents/{id}（OBS-D44-X3） |
| 5000 | 兜底"系统繁忙" | 未预期异常（含 NoResourceFoundException） |
| 拒绝面机器码（data 内） | TARGET_DELETED / OWNERSHIP_MISMATCH / EVIDENCE_NOT_EFFECTIVE | citation 校验三码（任务书 FABRICATED_CITATION 不存在=偏差已记 lateNotes） |
