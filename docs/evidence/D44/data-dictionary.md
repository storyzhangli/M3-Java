# D44 数据字典（答辩四件套·数据说明 | RAG 阶段收官权威版）

> 采集方式：`SHOW COLUMNS` / `information_schema` / DDL 文件实读（零推测）。
> 证据源：`probe\data-dict-shared-cols.txt`、`temp_docs\D44\ddl\d44_schema.sql`、PG information_schema 实查（2026-11-27）。
> 环境：MySQL 127.0.0.1:3306 / ai_db（root，密码 env MYSQL_PASSWORD 兜底）；PG 192.168.200.153:5432 / spring_ai（postgres，密码 User 级 env PG_VECTOR_PASSWORD；JDBC 必用 postgresql-42.7.11.jar）。

## 1. 存储拓扑与前缀隔离

```
ai_db (MySQL)                       spring_ai (PostgreSQL + pgvector 0.8.7)
├─ d44_ 前缀 5 表（D44 治理面新建）  ├─ d44_vector_store      ← D44 知识域（本轮 8 行冻结面）
├─ 共享业务表 7 张（前代沿用）       ├─ one_d44_vector_store  ← D44×百炼隔离轨（本轮 0 行）
│   knowledge_document /            ├─ d37_vector_store      ← D37 评测域（只读遗留）
│   document_vector_index /         └─ （D38–D42 各代 vector_store 表只读遗留）
│   document_chunk_index /
│   knowledge_document_page /
│   chat_session / chat_message /
│   prompt_template
└─ d37–d43 前缀遗留表（只读零触碰）
```

隔离纪律：D44 业务夹具一律 `d44-` documentId 前缀；清理谓词按前缀圈定（D44Cleanup 硬编码）；前代数据（D41-* 15 行、d32eval 24 chunk_orphan 等）零触碰。

## 2. PG 向量域（2 表，结构同构）

### 2.1 d44_vector_store / one_d44_vector_store

| 列 | 类型 | 说明 |
|---|---|---|
| id | uuid | 主键；= MySQL document_chunk_index.chunk_id（跨库关联键，chunkId 即向量行身份） |
| content | text | 片段原文（引用溯源回显体） |
| metadata | json | Spring AI Document metadata 序列化；**键名 camelCase**：`documentId`（非 document_id）、`version`、`strategy`、`chunkIndex`、`title` 等——对账/清理谓词按 `metadata->>'documentId'` 取值（D44Cleanup 首版列名错教训实证） |
| embedding | vector（USER-DEFINED，pgvector） | 维度 384（local-hashing）或 1536（远程轨，本轮 NOT RUN）；HNSW 余弦索引 |

生命周期：VectorIndexService 写=同事务先 DELETE 该 documentId 全部旧行再 INSERT（版本覆盖语义）；vi-delete=按 metadata documentId 全域 DELETE。检索面 `SimilaritySearchController` 走 Spring AI VectorStore 抽象（similarity/distance 双分）。

## 3. MySQL d44_ 治理面（5 表，DDL 全量见 temp_docs\D44\ddl\d44_schema.sql）

### 3.1 d44_document_sync——操作级同步状态机（D40 口径同构）

| 列 | 类型 | 语义 |
|---|---|---|
| id | BIGINT AI | PK |
| document_id | VARCHAR(64) | d44- 前缀=夹具域 |
| operation | ENUM('DELETE','UPDATE_CLEANUP') | 操作类型 |
| version | INT | 0=全版本(DELETE)；>0=目标旧版本(UPDATE_CLEANUP) |
| sync_status | ENUM('PENDING','SYNCED','FAILED') | 三态（KP4 冻结） |
| reason_code | VARCHAR(64) | 6 原因码；耗尽=RETRY_EXHAUSTED |
| attempt_count | INT | 失败累计；手动 retry 重置 0 |
| next_retry_at | DATETIME NULL | 退避到期时刻 |
| payload_hash | CHAR(64) | 排序 chunkId 集 SHA-256（操作快照指纹） |
| created_at / updated_at | DATETIME | — |

键：UK(document_id, operation, version)——同文档同操作同版本幂等唯一；IDX(sync_status, next_retry_at)——到期扫描。

### 3.2 d44_admin_audit——特权操作审计（access.enabled=true 才写）

actor_user_id / tenant_id / role(USER|ADMIN 服务端权威快照) / action / target_document_id / version / **result(ALLOWED|DENIED 双分支均留痕)** / reason_code / request_id(MDC 链路贯通) / created_at。本轮 C08 终值：146→233 行（矩阵期新增 87）。

### 3.3 d44_auth_principal——凭据表（结构同构 d39–d43，表级隔离）

**token_sha256**(PK，Bearer SHA-256 小写 hex，DB 零明文-H9) / user_id / tenant_id(硬隔离边界) / dept_id / device_scopes(JSON 数组，[]=仅公共*) / role ENUM(USER,ADMIN) / enabled(TINYINT，0=fail-closed) / expires_at(NULL=不过期；A6 过期探针夹具=2020-01-01)。

### 3.4 d44_table_extract——表格抽取记录（D41/d43 同构）

document_id + version + page_number + table_index **UK 四元组**（续表独立记录）；header_snapshot_json（跨页续表匹配键）；row_count/col_count；source_global_start/end（规范全文 UTF-16，与 Chunk.globalStart 同口径）；source_line_start/end；detect_flags（退化/异常码表）；confidence(HIGH/MEDIUM/LOW)；**continuation_of_table_id**（跨页续表回链，新记录+回链非合并）；status(ACTIVE|ORPHANED——TABLE_ORPHAN 对账标记)。

### 3.5 d44_table_row——表格数据行

**table_id**（→d44_table_extract.id 弱外键，同事务删；注意列名≠extract_id，首版清理脚本在此踩坑）；row_index(1-based，表头不占位)；cells_json（类型化数组 [{value,unit,type,isNull}] 按表头列序，缺失单元格 null 占位不串列）；source_line_number；record_hash（规范化 cells SHA-256，幂等/对账键）。UK(table_id, row_index)。

## 4. MySQL 共享业务表（7 张，前代契约沿用；列全量见 probe\data-dict-shared-cols.txt）

### 4.1 knowledge_document（本轮 18 行=冻结面：语料 3 ACTIVE + D41-* 15 DEPRECATED）

要点列：document_id **UNI**；source_system + source（双列，来源域）；version（当前版本指针）；status（导入处理态 PENDING→…）；**lifecycle_status**（ACTIVE/DELETED/DEPRECATED——注意：**OBS-D44-X1** src/main 零 DEPRECATED→ACTIVE 回写路径，seeding 复活需 DB 显式 UPDATE）；content_hash UNI（幂等键）；error_code/error_message；tenant_id/dept_id/device_id（权限元数据三元组，D39 契约）；**无 deleted 列**（软删语义由 lifecycle_status 承担——首版脚本在此踩坑）。

### 4.2 document_vector_index——文档级索引状态机

UK 语义(document_id, version, content_hash, strategy)；status(PENDING→RUNNING→COMPLETED/FAILED)；stage；attempt_count/next_retry_at（异步领取 R02 原子认领先例）；chunk_count。

### 4.3 document_chunk_index——片段账本（chunk_id **UNI**=PG 向量行 uuid 对账锚）

document_id + version + chunk_index + content(mediumtext) + chunk_content_hash + char_length + global_start/global_end（UTF-16 规范坐标）+ status(INDEXED)。删除闭环=先删 PG 向量再删本表行（或同事务），reconcile 双向对账 missing/orphan。

### 4.4 knowledge_document_page——分页正文（PDF 解析产物）

page_number 1-based；content(mediumtext)；blank/blank_page、character_count；parser_name/parser_version（溯源）；注意表内**同时存在 blank 与 blank_page、双 created_at 默认值**等历史演进冗余列（迁移遗留，读方以业务代码实际引用为准）。

### 4.5 chat_session / chat_message——会话域

session_id UNI；**owner_id**（非 user_id——首版脚本踩坑实证）；scene（**枚举已演进 8 值如 customer_service，'SERVICE' 非法——OBS-D44-X2 坑①**）；template_id/template_version（模板钉扎）。message：sequence_no 会话内序、role(user/assistant)、status、model_name、error_code。chat POST=**既有会话追加语义**（自定义 sid 被 4001 拒；省略 sid 服务端自动建——OBS-D44-X2 坑②）。

### 4.6 prompt_template——模板域

template_id UNI；scene IDX；content + variables(JSON)；version；status(ENABLED)；**deleted**(tinyint 软删列——与 knowledge_document 不同表软删策略不一致，属前代契约)。矩阵夹具域 d44e2e 前缀，本轮清理后 0 行。

## 5. 跨库关联与生命周期链

```
register(knowledge_document INSERT, ACTIVE)
  → import(page/chunk 派生 + d44_table_extract/row 钩子)
  → index(document_vector_index 状态机 → PG d44_vector_store 写入 + document_chunk_index 账本)
  → 检索(read PG) → ask(证据链)
  → 升版(v+1: UPDATE_CLEANUP 旧版本向量, 状态行回链)
  → vi-delete(DELETE 事件: PG 全删 + chunk 账本删 + 表格域删 + d44_document_sync DELETE-SYNCED)
      ⚠ OBS-D44-X4: 登记行 knowledge_document 与 sync 历史行不在闭环范围（DB 级清理通道兜底）
```

对账三轨（reconcile，d44_vector_store 默认前缀可切）：向量轨 orphan/missing（PG vs chunk_index）、计数轨 countMismatches（vector_index.chunk_count vs 实际）、表格轨 tableOrphans（extract vs page 坐标有效性）+ syncStuck（PENDING 超时）。

## 6. 本轮冻结面终态基准（cleanup 后，P4 对照）

| 域 | 权威值 | 取证 |
|---|---|---|
| knowledge_document | 18（语料 3 ACTIVE + D41-* 15 DEPRECATED） | post-cleanup-anchor.txt / demo S1 |
| d44_vector_store | 8 | 同上 |
| one_d44_vector_store | 0 | 同上 |
| document_chunk_index 语料域 | 8（orphan 24=d32eval 前代 noise 单列） | 同上 |
| d44_table_extract / row | 0 | 同上 |
| d44_document_sync 矩阵域 | 0（历史 3 行=更早正当增量） | demo S7 |
| prompt_template d44e2e 域 | 0 | c08-summary.json |
| 双锚 | 内容 c377bb2f…64fb（跨代恒等）+ ID de8d2f0c…6103 | kp2-dual-anchor.txt |
