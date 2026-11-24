# D41-R01 整改实施审查（implementation-review.md）

- 基线：`b76c106`（主迭代提交，整改书锁定点）→ 本整改段全部工作区修改
- 审查日期：2026-11-24（Asia/Shanghai）｜审查人：整改执行助手（代码事实+静态审查+自动化+在线四类证据分标）

## 1. 整改项闭环状态

| 项 | 整改要求 | 实施 | 证据类别与结果 |
|---|---|---|---|
| R03 | reExtract PDF 重建续表 ID 悬空修复 | `TableRecordService.reExtract` 第二遍 idRemap 回链 + `copyForRebuild` 不再复制旧 `continuationOfTableId`；映射缺失→warn+清空（T3） | 代码事实[已读 diff]；自动化[TableRecordRebuildTest 5/5，T1/T3 先红复现]；在线[r4：(14,15)→(16,17) 轮换、cont=16 指新 ID、旧 ID 泄漏=0、ACTIVE 悬空=0、行数 2/2 保真、旧行清零] |
| R02 | 表格专项 JUnit + 夹具/生成源码入库 + 真实执行日志三件套 | 4 个测试类 25 用例；12 夹具文件 + SHA-256 manifest + 生成器归档（本机字体依赖已在 summary 声明） | 自动化[78/78 全绿 EXIT=0]；三件套[test-command.txt/test-output.log/test-summary.md]；含 §4.1 同页反例不链断言（单测 + 在线 HTTP 双证） |
| R01 | 真实公开设备手册逐格对照 | 台达 ASDA-A2 官方应用技术手册（2008-11，410 页，文本层真 PDF）主路径 368 表全量解析；p396-398 三张电机尺寸规格表 225 格人工核定逐格对账 | 真实数据+人工核定+程序化 diff 五件套；**wrong_value=0**、value_hit 94、missing 131、碎片化域（异警处理/参数块）如实入 error-list E1-E6；PDF 原件因许可不入库（来源+哈希+最小对账数据入库） |

## 2. 修改文件清单与最小性论证

**主代码（2 文件，均为缺陷修复，无接口/字段/JSON 契约变更）：**
- `document/table/service/TableRecordService.java`：reExtract 追加第二遍回链循环（15 行级）；copyForRebuild 删除 1 行复制。语义与原注释"重建后按映射重写回链"一致，属实现补齐。
- `document/table/PdfTableExtractor.java`：linkContinuations 谓词追加页序条件 `t.getPageNumber() > previous.getPageNumber()`（1 行 + 裁决注释）。修复整改书 §4.1 同页误链反例；跨页正例不受损（夹具+在线双证）。

**测试代码（5 文件新增）+ 夹具（12 文件）**：见上表；夹具为受控构造（synthetic），与 R01 真实手册两轨分开，truth 先行未迁就抽取。

**未改动**：Controller 端点、TableView 契约、d41_ 表结构、导入/删除/同步管线、无关业务逻辑——git status 核验仅上述文件集。

## 3. 已知边界与风险（如实记录）

1. **reExtract 路由契约**：`POST .../tables/extract` 按 `source 含 "pdf"` 判 PDF 重建路径（javadoc 既有声明"PDF 坐标路真重解析需重新上传原件"）。source 不含 pdf 的 PDF 文档走文本重解析分支将得 0 表——本轮在线核验第一次执行实测触达该边界并以正确 source 复跑坐实。**属主迭代既有设计契约，非本次引入；如需收口建议后续迭代在端点响应 data 中显式回显 route 字段**（涉及 API 契约变更，未经要求不动）。
2. **内容哈希全局去重**：同字节文件二次导入被 findByHash 短路（既有 D40 契约），核验采用 PDFBox re-save 唯一变体触发真实链路；变体仅重写交叉引用，版面语义等价，过程记录于 r01v*.pdf 工具注释。
3. 历史库中曾存在修复前 P1 期入库文档的重建悬空行（本轮终态核验时已随删除管线物理清空，当前 `d41_table_extract` 全表 0 行、悬空 0）；**零触碰基线增量**：knowledge_document 由 3 → 10（R03 在线核验导入的 7 篇文档 tombstone，属删除闭环既定语义，非失控写入）。
4. R01 真实手册网格还原率 41.8%（受控夹具 100%）：差距已量化归因（E1-E5），改进清单三条留待独立迭代，本整改不扩范围。
5. `linkContinuations` 语义变化对 `d41-pipe-continuation.md` 原"续表"展示预期的影响：truth 无回链断言、P1 仅 SMOKE 级、在线实证 linked=0——无证据冲突（整改书反例规则优先）。

## 4. 验证矩阵（实际执行）

| 动作 | 命令/方式 | 结果 |
|---|---|---|
| 专项测试 | mvn -B test -Dtest=4 类 | 25/25 PASS |
| 全量回归 | mvn -B test（日志入库） | **78/78 PASS，EXIT=0** |
| 在线 R03 | d41_r03_online_verify.ps1（r4 新代码） | 10/10 PASS |
| 同页反例 HTTP | 同上 step5 | linked=0 PASS |
| 删除闭环回归 | 同上 step6 | tables 归零 PASS |
| 夹具防篡改 | fixtureManifestIntegrity 用例 | 12 文件 SHA-256 全一致 |
| 真实手册 | D41RealManualProbe | 368 表/410 页/4.5s，diff 225 格程序化落盘 |

## 5. 审查结论

三项整改均达成验收口径；主代码改动最小且未触契约；全部证据（自动化/在线/真实数据/人工核定）可复算；未验证残留项已在 §3 显式声明。可提交。
