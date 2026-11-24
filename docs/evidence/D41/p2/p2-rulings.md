# D41 P2 收口记录（导入钩子 + 同步/对账绑定 + 配置追加）

日期：2026-11-24 ｜ 实例：r3（8080，AI_MODE=mock，含 P2 全部代码）｜ 回归：mvn test 53/53 EXIT=0（两轮实测）

## 交付清单（全部已实施并验证）

| # | 项 | 文件 | 状态 |
|---|----|------|------|
| 1 | 导入钩子 | DocumentImportService（构造 +TableRecordService；handlePdf anyUsable 域内钩子、handleText 钩子、extractTablesQuietly 静默降级包装） | 冒烟 PASS |
| 2 | 表格事务独立 | TableRecordService.extractAndPersist → **REQUIRES_NEW**（裁决 D11） | 冒烟 PASS |
| 3 | 删除管线清表 | VectorIndexStateTx.deleteDocumentRows +=deleteAllForDocument；deleteOldVersionRows +=deleteForVersion（新增方法） | 冒烟 PASS |
| 4 | 对账扩展 | ReconciliationReportView +tableOrphans+driftTableOrphan+total/refresh；VectorReconciliationService +DRIFT_TABLE_ORPHAN 常量+第 7 检测段+ORPHANED 标记补偿+构造 +TableExtractMapper | 冒烟 PASS（field=True smokeOrphans=0） |
| 5 | 配置追加 | application.yml app.ai.document.table{enabled,ocr.enabled}（带 H1/H3 注释） | 冒烟 PASS |
| 6 | 测试构造点同步 | RecoverStuckTasksScopeTest 3 处构造 +1 参（reconcile ×2、indexService ×1） | 53/53 PASS |

## 在线冒烟终态（docs\evidence\D41\p2\smoke-p2.json，脚本 docs\temp_docs\D41\d41_p2_smoke.ps1）

7/7 PASS：register-import-completed（COMPLETED dup=False）→ tables-listed（1 表，TableView 16 字段）→ dual-track-offsets（start=10 end=157 UTF-16）→ rows-listed（3 行，record_hash 前缀 5ec5c7f5b236）→ anon-bypass-default-disabled（H1 预期旁路）→ reconcile-tableOrphans-field（零误报）→ delete-clears-tables（tablesAfter=0）。

零触碰复核：kd=3、dvi=7、dci=32、d41_table_extract=0、d41_table_row=0（冒烟残留已清）。

## P2 期间实证的真缺陷与修复（非猜测，均有证据链）

1. **删除幂等短路表行泄漏（P0 级，已修）**：deleteByDocument 步骤 0 短路谓词=（chunk 空 ∧ task 空 ∧ PG 空 ∧ sync SYNCED），不含表格行维度。场景实证：r2 首轮脚本对未登记文档空删（sync→SYNCED），r2 二轮导入落表后被短路 return，表行永久泄漏（文档行 COMPLETED 且版本一致，reconcile TABLE_ORPHAN 亦检不出）。修复=DocumentVectorIndexService 短路谓词追加 `tableRemaining==0`（注入 ObjectProvider<TableRecordService>，null 时按 0 保守原语义）。r3 复验：删除后表行归零 PASS。测试构造点（L55 newService）同步 +1 参。
2. **D11 事务传播裁决**：任务书初读"表格随导入同事务"与 D1 独立分支解耦红线冲突——同 REQUIRED 传播下表格异常会将外层正文事务标记 rollback-only 连坐正文。裁决=REQUIRES_NEW：表格失败整体自回滚、正文不受影响；不一致窗口由 TABLE_ORPHAN 对账兜底（D40 哲学）。代码注释已留痕。
3. **误报修正（留痕防再犯）**：初判"deleteByDocumentIds 系臆造列"不成立——TableRowMapper 该方法为 document_id 子查询（正确）；失败源于 harness 手写 SQL 直查 d41_table_row.document_id 列。最终实现仍采用"取表 id→deleteByTableIds"（与 deleteForVersion 同型、范围由 table_id 界定 H6）。
4. **PS 断言陷阱（工具链教训）**：`Step 'x' (RowCount $d -eq 0)` 函数调用未加括号致 `-eq` 被吞为函数参数——数据已正确清零仍报 FAIL。凡函数参与比较必须 `(fn arg) -op val`。

## 已知边界（归后续阶段）

- 匿名/越权 4030 拒绝矩阵=access.enabled=true 态，归 P3 接线验收（默认 disabled 旁路已实证为 H1 预期）。
- TableView 的 header 字段名（REST 16 字段快照已录证据）与 detectFlags 展示口径在 P3 单测钉死。
- knowledge_document/dvi/dci 表名无前缀（D33 期沿用），d41_ 前缀仅治理表+表域——P4 脚本对账 SQL 注意。
