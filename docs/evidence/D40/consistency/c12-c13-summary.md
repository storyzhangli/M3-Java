# C12/C13 一致性五类漂移全命中 + 修复走管线（P5 取证汇总）

> 模块：D40_DUDACR ｜ 实例：injB（注入态）+ r4（修复代码后默认态）｜ 日期：2026-11-23
> 关联裁决：probe\kp1-kp4-adjudication.md §KP5（受控单步构造授权）
> 原始数据：exceptions\e5-instance-b.json、exceptions\c12b-instance-b.json、
> consistency\{detect-report.json, repair-recheck.json, c12b-detect.json, c12b-drift-snapshot.json, c12b-drift-construct.json, c12b-repair-recheck.json}

## 1. 五类漂移命中矩阵（C12）

| 漂移类型 | 构造方式 | 命中证据（driftSummary 当时值） | 修复动作（管线） | 复查归零 |
|---|---|---|---|---|
| DRIFT_META_ONLY | ① d32eval 域既有 24 行（真实历史漂移，域外如实列出）；② d40 域：PG 删 j v1 1 行（KP5 单步构造+留痕 c12b-drift-construct.json） | d40 域 detect: driftMetaOnly=25（24+1） | consistency 非 dryRun prefix=d40-doc- → indexChunks(forceReindex) Outbox 快照重建 | post: 24→仅剩域外 24；j 回 2/2 ✓ |
| DRIFT_VECTOR_ONLY | d40 域：MySQL 删 k 全部关联行（chunkId 先留痕） | c12b detect: driftVectorOnly=2 | consistency 修复 → deleteVectorsByIds 补偿删除 | post: 0 ✓（orphan=0） |
| DRIFT_STALE_VERSION | E5 注入：cleanupSuperseded 阶段 delete#1 抛（装饰器，零裸 SQL） | e5 detect: driftStaleVersion=2（h v1 PG 残留 2 行） | consistency 修复（STALE=orphan 子集）→ deleteVectorsByIds | post: 0 ✓ |
| DRIFT_VERSION_MISMATCH | d40 域：指针 j→9、k→3（KP5 单步 UPDATE+原值留痕） | c12b detect: driftVersionMismatch=2 | stateTx.repointVersionPointer（setVersionPointer 覆写语义） | post: 0；GET /documents j.version=1、k.version=2 ✓ |
| DRIFT_SYNC_STUCK | E5 注入产生的 h UPDATE_CLEANUP FAILED 到期行 | e5 detect: driftSyncStuck=1 | POST /sync/retry h → retryCleanupVersion → SYNCED attempt=1 | post: 0 ✓ |

全域 dryRun 收敛终态（r4 修复复验）：`total=28`（= 域外 d32eval 既有 24 META_ONLY + 4 countMismatch），
d40 夹具域五类全部 0，与 P5 核查基线一致——**修复只作用于 d40 前缀域，域外漂移零触碰**（H6 scopePrefix 生效实证）。

## 2. 修复过程发现并实修的两个产品缺陷（第一遍失败如实记录）

1. **repoint 静默失效**：`VectorIndexStateTx.repointVersionPointer` 初版复用
   `bumpVersionPointer`（`WHERE version < target` 单调前进守卫，服务 SWITCH 语义）；
   修复漂移指针需**回退**（9→1/3→2）被守卫拦为 0 行且无异常 → 复查仍见 VERSION_MISMATCH=2、pointer_j=9。
   修复：`KnowledgeDocumentMapper` 新增 `setVersionPointer`（无守卫覆写，仅修复路径调用），
   `repointVersionPointer` 切换调用；单测回归 42/42 绿。
2. **META 重建被幂等短路吞掉**：`applyCompensation` 缺失重建调 `indexChunks`，
   命中 D31 幂等短路三元组（COMPLETED ∧ payloadHash ∧ 关联数——该三元组按 E4 冻结语义不含 PG 侧），
   返回 idempotent=true 零写入 → PG 缺行永不回填。
   修复：`indexChunks` 增加 `forceReindex` 重载（默认重载行为逐字节不变；仅一致性修复路径传 true）。
   第一遍失败数据保留于 exceptions\c12b-instance-b.json（recheck 当时 META=25/VERSION=2 未归零原样）。

## 3. C13 修复全部走管线证明（无裸 SQL 直改业务表）

修复动作仅三个入口（grep 代码实证）：
- `VectorReconciliationService.applyCompensation` → `indexService.deleteVectorsByIds`（既有补偿删除，D31）
- → `indexService.indexChunks(..., forceReindex=true)`（既有入库管线，稳定 ID upsert）
- → `stateTx.repointVersionPointer` → `documentMapper.setVersionPointer`（MySQL 本地事务，@Transactional(rollbackFor)）

修复侧（main/java）**零** `UPDATE knowledge_document SET`/`DELETE FROM d40_vector_store` 类绕过管线的写路径；
唯一 DB 级单步操作发生在**故障构造侧**（temp_docs harness D40DriftConstruct，先留痕后构造，KP5 裁决，
不属产品代码）。

## 4. 验收对照

- C12 全命中：五类各自 ≥1 命中（本表第 3 列）→ PASS
- C13 修复归零 + 无裸 SQL：本表第 4/5 列 + 第 3 节 → PASS
- H6 夹具域隔离：无 prefix 非 dryRun → 4001（e5-instance-b.json 'H6:repair-without-prefix'）→ PASS
