# D41 状态文件 — R02 交付物补齐记录（追溯式补齐，不伪称当日记录）

- 迭代：D41 · 切片 R02（交付规范缺口补齐）
- 补齐日期：2026-11-25（本文件为 D41-遗留整改任务书 §3.3 要求的**补齐件**；
  所记录工作发生于 `b479680` 提交及之前，事实来源=该提交树内证据文件与代码本体，
  逐条可复核，非事后补编结论）
- 基线 commit：`b479680`（D41-R01 整改收口提交，任务书 §1 认定）

## 1. 已闭环事实清单（R02 补齐对象 = 这些事实的交付物欠账）

| # | 已闭环项 | 代码/证据位置（b479680 树内） | 状态文件欠账 |
|---|---|---|---|
| 1 | 续表 ID 映射修复（reExtract 二段式：先插新行、idRemap 就绪后第二遍重建回链；映射缺失清空+log.warn 不静默） | `TableRecordService.java` L144–165；回归=TableRecordRebuildTest | R03 修复注释已写明 R01 整改来源 |
| 2 | 表格专项测试 25 用例（table 包 4 类：PdfTableFixtureTruthTest 8 / TableCellTypingTest 6 / TableRecordRebuildTest 5 / TextTableExtractorTest 6） | `src\test\java\com\zl\d41\table\` | 本文件 §2 |
| 3 | 受控夹具 12 件 + FIXTURE-SHA256 manifest + 生成器源码 | `src\test\resources\d41-table-fixtures\`；`fixtures\D41TableFixtureGenerator.java` | 本文件 §2 |
| 4 | 真实公开手册 225 格逐格对账（ASDA-A2 8MB/404 页，SHA=6050f8f3…，value_hit 27/unaligned 67/missing 131/wrong 0，网格还原率 41.8%） | `docs\evidence\D41\R01\`（source-cells/extracted-cells/cell-diff/error-list/online-verify 五件套） | 本文件 §3 |
| 5 | 冻结参数（ROW_TOL=3.0/COL_GAP=25.0/COL_ANCHOR_TOL=6.0/MIN_TABLE_ROWS=2/MIN_TABLE_COLS=2/LOW_CONF_SHARE=0.3，PDFBox 3.0.7 唯一表格能力源） | `TablePatterns.java` 注释与常量 | 本文件 §4 |
| 6 | 偏差裁决留痕（REQUIRES_NEW 与任务书初读"同事务"偏差=D11 裁决；PDF reExtract=结构化行幂等重建非坐标重采） | `TableRecordService.java` javadoc | 本文件 §4 |
| 7 | L1–L5 能力分层声明与诚实边界（OCR/Word/扫描 PDF 显式降级，IMAGE_TABLE_NOT_PARSED/WORD_NOT_SUPPORTED 占位） | Controller/Extractor javadoc 与 yml 注释 | 本文件 §4 |

## 2. 测试与夹具口径（b479680 树实况）

- 全量 78 用例 / 14 类（table 25 + sync/audit 53），`mvn -B -f D41\pom.xml -pl D41_TASIIEM test`
  于 2026-11-25 复核 PASS（r03-mvn-baseline.log，整改前锚定）。
- 夹具性质=**synthetic/controlled fixture**（PDFBox 绘制/Java2D 渲染受控生成，真值先行，
  禁以抽取结果反推真值）；完整性由 FIXTURE-SHA256.txt manifest 钉死；生成器可复现。
- 与真实手册轨分开管理：夹具轨证明机制正确性，R01 轨证明真实文档还原度（41.8% 基线）。

## 3. 225 格对账口径（R01 冻结定义）

- 格集合：p396 15×4 / p397 15×5 / p398 15×6 = 225（Model 标签列不计入）。
- 四分类：value_hit（值对且列对）/ value_hit_column_unaligned（值在但列错位）/
  missing_in_extraction（整行或值缺失）/ wrong_value（值形态命中行但数值异）——
  复算工具 `d41_r03_cell_diff.ps1` 已与本口径逐格校准 diff=0（calib-prerun-cell-diff.json）。
- 整改结果见 `D41_state_R03.md` §2（195/30/0/0，86.7%）。

## 4. 交付物补齐结果（本次 R02 切片实际产出）

| 交付物 | 路径 | 状态 |
|---|---|---|
| 状态文件 R02（本文件） | `docs\evidence\D41\iteration_records\D41_state_R02.md` | 已补齐 |
| 状态文件 R03 | `docs\evidence\D41\iteration_records\D41_state_R03.md` | 已交付（随 R03 切片） |
| 技术文档 D41-01（全量信息与操作） | `docs\proj_docs\D41-01_设备手册表格抽取全量信息与操作.md` | 已补齐 |
| 技术文档 D41-02（技术文档与实践指南） | `docs\proj_docs\D41-02_设备手册表格抽取技术文档与实践指南.md` | 已补齐 |
| iteration_records 目录 | 原为空（任务书 §3.3 缺口 D1 实锤），现有 R02/R03 两件 | 已建 |

## 5. 诚实声明

1. 本文件为**追溯补齐件**：记录的是 b479680 前已完成并有盘上证据支撑的事实，
   未新增任何当时未做的验证，未修改任何口径。
2. 缺口成因：R01 整改切片将文档预算投入证据链（五件套）与测试，双文档与状态文件
   未随提交产出——属流程违规，由任务书 D1/D2 指出，本次补齐闭环。
3. 本文件所有"代码位置"引用行号以 2026-11-25 工作区版本为准；b479680 树内对应内容
   一致性由 `git show` 可复核。
