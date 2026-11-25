# D41 状态文件 — R03 解析能力整改（T1–T5）

- 迭代：D41 · 切片 R03（遗留整改）
- 日期：2026-11-25
- 基线：整改基线 `b479680`（任务书 §1），实际执行基线 HEAD=`2f55b10`
- **基线漂移记录（如实）**：`b479680..2f55b10` 之间为 D42_RERANK 迭代 4 笔提交
  （504ffee 主提交 / f441c2a 状态补记 / 用户追加 .gitignore）与本次工作区改动。
  祖先关系已验证（`git merge-base --is-ancestor b479680 HEAD` exit=0）。D42 提交不触碰
  table 包，解析代码与本状态文件描述一致，无冲突面。

---

## 1. 任务与结论总览（四态标注）

| 任务 | error-list | 方案（坐标级根因驱动） | 状态 |
|---|---|---|---|
| T1 表头巨 span 型号列名锚定 | E1 右侧列丢失 | refineLine 型号分裂 + 表头锚定（删全局聚类+subList） | 已实施·已验证 |
| T2 表头行升格修复 | E2 p397/398 首行升格 | 表头分裂后自然进入表区（**机理与任务书假设偏差，见 §3**） | 已实施·已验证 |
| T3 长标签行 x 漂移回拉 | E3 LL 行标签漂移 | 表头锚区间归列（intervalColumn） | 已实施·已验证 |
| T4 参数块碎片合并 | E5 大表断裂碎片化 | mergeAdjacentFragments（lineIndex 邻接+碎片形态谓词） | 已实施·已验证 |
| T5 复合单元格黏连分隔 | E4 标签+数值同 span | splitStickyValue（空白分界+数值后缀，单位契约保护） | 已实施·已验证 |
| E6 字体噪声 | — | 本轮不改，影响面记录（§5） | 已记录 |

## 2. 复算权威对照（225 真值格，同一工具同一口径）

工具：`docs\temp_docs\D41\r03\d41_r03_cell_diff.ps1`（口径=R01 cell-diff.json meta.diffMethod，
整改前经逐格校准 classDiffCells=0 建立，见 `calib-prerun-cell-diff.json`）。

| 类别 | 整改前(R01) | 整改后(R03) | Δ |
|---|---|---|---|
| value_hit | 27 | **195** | +168 |
| value_hit_column_unaligned | 67 | **30** | −37 |
| missing_in_extraction | 131 | **0** | −131 |
| wrong_value | 0 | **0** | 0（保真红线守住） |

- 225 格结构（R01 冻结）：p396 60 格（15 行×4 型号列）/ p397 75 格（15×5）/ p398 90 格（15×6）；
  工具按"行标签+值+真值集形态"三维分类，**行标签列（Model 列）不计入对账格**——
  前后口径同源可比（R01 cell-diff.json 即此结构，校准 diff=0 复证）。

- 网格还原率（value_hit/225）：41.8% → **86.7%**（复算值，非估算）。
- 值在场率（hit+unaligned）：41.8% → **100%**（225/225 格全部有值）。
- 剩余 30 unaligned 定位（post-cell-diff.json）：p397 `C1110ES` 列 15 格 + p398 `C111020□S`
  列 15 格——**R01 人工抄录真值与 PDF 文本层拼写差异**（文本层实测 `C11010ES`/`C11020□S`，
  spans dump 为证）。抽取侧列归位实际正确（值落在真值同位列）；真值口径文件按 §6 纪律不改，
  待用户裁决真值转写勘误后可复算归零。该差异非解析能力缺陷。

## 3. 机理修正记录（任务书假设 vs 实测证据，诚实纪律）

1. **T2 根因偏差**：任务书 §3.2 假设 p397/398 首行升格源于"页首图形区矢量线条 y 连续
   与表头区粘连"。spans dump 实测：页首图形区**零文本 glyph**（矢量图形不产文本行），
   真实机理=p398 表头行粘连为**单巨型 span**（spans=1 < MIN_TABLE_COLS=2）被 detectTables
   滑窗排除在表区外 → 首数据行 LC 升格。修复按实际机理实施（T1 表头分裂后表区自然含表头），
   **未实施**对"图形区 y 密度裁剪"的推测性代码——判据（p397 15 行归表 / p398 表头非数据行）
   已达成，若未来出现真实图形粘连样本再按证据补防。
2. **T1 病灶精化**：任务书表述"列锚聚类受漂移污染"成立，但截断路径更具体——
   `anchors.subList(0, baseCols)` 在伪锚挤占后把**右侧真实列锚整体丢弃**。修复直接
   废除"全行贪心聚类+截断"，锚=表头行（refine 后）span starts；数据行 x 漂移 ±6–8pt
   （p396 实测 196.3/204.6/208.0/209.3）由区间归列吸收。
3. **T3 与 T1 同机制**：长标签回拉无独立代码——表头锚定+区间归列后，LL 行标签 x=56.7/67.1
   （锚 0=93.5 以左）自动归 c0，数值 196–209 自动归 c1。如实记录：不存在"独立回拉逻辑"。

## 4. 实现清单（全部在 table 包内，契约零变更）

| 文件 | 变更 | 契约影响 |
|---|---|---|
| PdfTableExtractor.java | Span 增 glyphs 字段（内部 record，不外泄）；toLine 携带字形明细并调 refineLine（幂等，detectTables 入口兜底再精化）；refineLine=isModelHeaderLine→splitSpanForHeader（T1）/splitStickyValue（T5）；buildTable 锚点=表头行 span starts（删除全局聚类+subList）；mapLineToCells 区间归列 intervalColumn（T3）；mergeAdjacentFragments+canMergeFragments+appendFragment（T4，extract 主路径接线） | TableView/TableCell/DetectedTable 对外字段、Controller、JSON、d41_ 表结构均未触碰 |
| TablePatterns.java | 新增 MODEL_COL_TOKEN / MODEL_COL_START / NUMERIC_SUFFIX / FRAGMENT_MERGE_MAX_GAP_LINES=1 / FRAGMENT_MAX_REGION_LINES=2 / FLAG_FRAGMENT_MERGED；**既有冻结阈值 ROW_TOL/COL_GAP/COL_ANCHOR_TOL/MIN_TABLE_*/LOW_CONF_SHARE 全部未改** | 纯新增 |
| PdfTableR03MechTest.java（新） | 8 用例：T1/T2/T3/T4/T5 各先红复现 + T4 间隙防御 + T5 单位/型号/日期防御（合成坐标取自 p396–398 真实 dump） | 测试新增 |

测试纪律执行：T1–T5 每项先红（r03-t1t5-red.log：8 run/5 红）后绿（r03-t1t5-green.log：8/8 PASS）。

## 5. 回归与验证记录

1. **全量 mvn -B test**：整改前基线 78/78（r03-mvn-baseline.log，EXIT=0）→
   整改后 **86/86 PASS，BUILD SUCCESS**（r03-mvn-full-post.log）——既有 78 用例零退化
   （含 12 夹具逐格对账、类型化、续表、reExtract 重映射），新增 8 全绿。【已执行·通过】
2. **真实手册全书重抽**：D41RealManualProbe（同代码）→ `extracted-cells-post-raw.json`
   （353 表 vs 整改前 368 表；p396 5 列 15 行 / p397 6 列 15 行 / p398 表头 Model+6 型号列
   15 行，flags 干净）。【已执行·通过】
3. **T4 度量**（pre=extracted-cells-raw.json，post=同上）：全书表数 368→353；
   单行碎片表 214→117（−97）；全书落库行数 982→1418（**+436=零丢失合并**，伪表头降格保留）；
   p345/p346/p347 由 0/1 表恢复为 1/2/2 表（表头锚定附带收益）。【已执行·通过】
4. **在线 mock 闭环复跑**（r5 实例，spring-boot:run，AI_MODE=mock，零 Key 零外网）：
   `online-verify-r03rerun.json` **11/11 ALL-PASS**——import→首轮回链→rows 可读→
   reExtract 双轨偏移(158,159→160,161)→回链重映射→悬空=0→旧行清零→行数一致(2/2,2/2)→
   同页反例不链→删除清表→终态归零。【已执行·通过】
   复跑排障记录（三轮）：v1 五项 FAIL——根因①同字节导入命中 contentHash 去重
   （`duplicate:true`，既定设计非缺陷）致新 doc 空表；②`$env:MYSQL_PWD` 未设致 DB 查询
   全 −1；③rows 端点 URL 笔误、`rows` 为 MySQL8 保留字、4030 步骤自造（先例十步无此项，
   同租户读 200 是正确行为）。v2 用变体夹具（原字节+ASCII 尾注）真实走导入路，主链转绿但
   rows 端点仍错。v3 修正后 11/11。**全程无产品回归项**——失败均为验证脚本缺陷，
   修正脚本后一次转绿。
5. **真实手册在线核验**：**NOT RUN，实证边界**——`POST /api/v1/documents/import` 对
   8.5MB 手册返回 `code=4001 文件大小超过服务端上限（2097152 字节）`（服务端行为正确）；
   在线核验 D41-R03RE-MANUAL 表数 0 即此因。提升上限属配置变更越界，按 §6.2 纪律不实施；
   真实手册解析质量以 §5.2/§2 离线探针 + cell-diff 为权威证据。探针响应样本已存
   `online-real-manual-r03.json`（记录 4001 拒绝实况，非成功证据）。

## 6. E6 字体噪声影响面（本轮记录，不改）

实测计数（`docs\temp_docs\D41\r01\tools\probe-run.log`，Select-String）：
`No Unicode mapping ... in font JOMKAB+Wingdings-Regular` WARN **50 条**（计数窗口=26KB
截断日志内，即 error-list R01 记录"数十条"的同一来源）。R03 复跑（stderr 完整重定向
`docs\temp_docs\D41\r03\probe-stderr-rerun.txt`）**0 条**——该告警为 WARN 级且复跑未见重现，
提示与具体页范围/字体加载路径相关（R01 跑批覆盖页集与探针参数差异），非 R03 改动引入。
对文本层影响=个别符号缺映射，p396–398 目标表无数值字符受影响（225 格 wrong_value=0 佐证）。
后续如需符号级还原，候选方案=字体映射缺失字符以 U+FFFD 显式标记（待立项）。

## 7. Word/OCR 裁决（维持）

Word 维持 WORD_NOT_SUPPORTED 显式降级；OCR 维持配置关断（DOCUMENT_TABLE_OCR_ENABLED:false）
占位不接线——本轮无接线必要性判断（未遇到"必须 OCR 才能补齐的 225 格样本"）。

## 8. wrong_value 保真核验

post-cell-diff.json wrong_value=0：数值/型号字面全程无改写/舍入/截断（T5 切分按字形 x
重排边界、不触碰字符序列；splitStickyValue 无法判定的组合保留原串）。

## 9. 残留风险

1. 真值 30 格列名拼写差异待用户裁决（§2），非代码缺陷。
2. `NUMERIC_SUFFIX` 千分位逗号（`1,480.5`）形态未出现在 225 格样本，T5 切分对该形态
   按"完整数值后缀"处理——更大样本集上线后需复扫。
3. T4 邻接谓词 lineIndex 间隙 ≤1 基于"独立表间必有空行"经验；跨章节紧邻表理论可误并
   （两碎片表恰为独立 2 行表且行间无空行），已由 colCount 相等+双碎片形态双谓词压低概率。
4. refineLine 幂等性依赖"分裂后行不再命中表头/黏连谓词"，已按 t5 防御用例钉住单位契约
   （`5.5 mm`）与型号串（`C11010ES`）不分裂。
5. 全书 155 页含表页统计口径变化（368→353 表、行数+436）会影响下游对账基线数字——
   D41 对账 SQL 按 document 维度而非全局常数断言，无既有测试依赖全书计数（86/86 实证）。
