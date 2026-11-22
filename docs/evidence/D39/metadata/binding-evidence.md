# C06 元数据绑定核验（#238/#239 实测汇总）

## 绑定链（注册→MySQL→入库→PG metadata）

1. `POST /api/v1/documents/register`（body 含 tenantId/deptId/deviceId）→ `knowledge_document` 三列落值（D39KdColumns ALTER 后；6 夹具行=9-3 实数核验，清理后回 3 行冻结 NULL）。
2. `POST /api/v1/vector-index/index` → `DocumentVectorIndexService` 经 `ObjectProvider<KnowledgeDocumentMapper>` 查三列注入 metadata 三键（有值才写；`"*"` 显式写入）→ `one_` 轨同构注入（NOT RUN）。
3. 未传三字段=三列 NULL=metadata 无键（D3 无标签态，冻结 8 行实测保持无键）。

## 实测证据

- **PG 侧 12 行三键全量核验**：`fixture/d5-fingerprint.txt` 逐行 `T/D/dev`（12 夹具行全部打标、8 冻结行全部 null/null/null，TAGGED_ROWS=12/20）。
- **API 响应侧核验**（enabled=false 匿名 top3 探测，2026-11-22 17:14）：`data.matches[0].metadata={tenantId:"T1",deptId:"D1",deviceId:"DEV-A"}`；冻结行 metadata 无三键——与 D3 语义一致。
- **关键词路一致性**：hybrid T7 "D2P-771" userA 零命中/userB 必现（`security/t7.jsonl`）⇒ ChunkFields 三字段与 PG metadata 同源（若倒排加载缺键，fail-closed 会导致 userB 也查不到，与实测矛盾）。
- 显式通配：F4（T1/*/*）与 F6 device（*）在谓词 `eq("*")/in(dev,'*')` 下按预期可见（t6.jsonl 正证）。

结论：注册三字段 → PG 三键 → 双路过滤全链一致，C06=PASS。
