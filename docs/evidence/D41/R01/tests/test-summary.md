# D41-R01 整改测试执行摘要（R02/R03）

- **执行时间**：2026-11-24（Asia/Shanghai），执行机 ZL（Windows）
- **执行方式**：真实执行 `mvn -B test`（命令见 `test-command.txt`，完整原始输出见 `test-output.txt`（`.log` 原件因仓库 `*.log` 忽略规则以 `.txt` 入库，字节内容一致），未做任何裁剪）
- **代码基线**：HEAD=`b76c106`（主迭代"D41 项目迭代：设备手册中的表格与结构化信息"）之上的整改段工作区修改（R03/R02 修复+新增测试，生成时未提交）
- **退出码**：`EXIT=0`（`BUILD SUCCESS`，与日志末行一致，非人工推断）

## 结果总览

| 口径 | 数量 |
|---|---|
| 全量回归（模块 D41_TASIIEM 全部测试类） | **78 / 78 通过，0 失败 0 错误 0 跳过** |
| 其中整改前基线（10 个 sync/index 域测试类） | 53 通过（存量无回归） |
| 其中本次新增（4 个表格专项类） | 25 通过 |

## 新增测试类与整改项映射

### TableRecordRebuildTest（5 用例）— R03 续表引用悬空修复回归
| 用例 | 验证点 | 红→绿证据 |
|---|---|---|
| T1 continuationRemappedToNewId | reExtract 后 `continuationOfTableId` 必须经 idRemap 重映射为新表 ID | 首跑红（实测携带旧 ID=10）→修复后绿 |
| T2 nonContinuationNoPatch | 非续表不产生多余回链 patch | 绿 |
| T3 mappingMissingNullified | 映射缺失时悬空引用清空为 null + log.warn | 首跑红（实测携带旧 ID=999）→修复后绿 |
| T4 rowRebindToNewTableId | 数据行重挂新表 ID、值/hash 保真 | 绿 |
| T5 txBoundaryAndFailureAtomicity | @Transactional(rollbackFor=Exception.class) 反射断言 + 异常中断不留下半态 | 绿（DB 原子终态由在线核验补证） |

### PdfTableFixtureTruthTest（8 用例）— R02 真值驱动逐格对账
- 7 个受控构造 PDF 夹具（`src/test/resources/d41-table-fixtures/`）逐一按 `fixture-truth.json` 真值核对：表头 equals、truthFlags 全含、数据行数（非空>1 格落库口径）、逐格 value/unit/isNull、缺格 null 占位不串列、MERGED_CELL/IMAGE_TABLE_NOT_PARSED/N/A 降级标记、跨页续表回链+CONT_REPEAT_HEADER、重复表头不混入数据行、无表格页零检出。
- 第 8 用例 = **FIXTURE-SHA256.txt manifest 逐文件哈希一致性**（受控夹具防篡改自证）。
- 比对规则与主迭代 P1 冒烟（D41P1SelfTest，9/9 已通过）同源移植，**真值先行，未以抽取结果反推真值**。

### TextTableExtractorTest（6 用例）— R02 文本表与续表反例
- 管道表结构+空寿命格 isNull；tab 表；缺列补 null 不串列+COLUMN_MISALIGNMENT；
- **R02 红线反例**：`d41-pipe-continuation.md` 同页两张同表头表 → linkContinuations 后两表均**不得** isContinuation、不得带 CONT_REPEAT_HEADER（配合 PdfTableExtractor 页序谓词修复）；
- 正例对照：跨页同表头仍回链（宁漏链不误链不损伤真实续表）；纯散文零检出不伪造空表。

### TableCellTypingTest（6 用例）— R02 类型化冻结口径
- 空格/紧贴单位拆分；**"1850" 纯数字哨兵不得误拆 unit=0**（主迭代已修缺陷防回归）；E-01/SP-0102 code 前导零保真；空串=缺格 vs "—"=不适用（string）状态区分；复合区间值（-20℃ to 60℃ / 3.7-4.2 kW）整体保留 string 不拆散；"1100 N·m"（·不在单位字符类）整体 string 属 KP4 冻结边界，如实断言。

## 夹具与生成源码入库说明（R02 可复现性三要件）
1. **测试代码**：上表 4 个类（`src/test/java/com/zl/d41/table/`）。
2. **夹具**：`src/test/resources/d41-table-fixtures/` 共 12 文件（7 PDF+2 MD+truth+manifest），性质=**synthetic/controlled fixture**，非真实设备手册（R01 独立轨）。
3. **生成方式**：`src/test/java/com/zl/d41/table/fixtures/D41TableFixtureGenerator.java`（归档自 temp_docs；依赖本机 `C:\Windows\Fonts\simhei.ttf/msyh.ttc` 渲染中文，重生成结果不保证跨机逐字节一致——**以入库夹具+SHA-256 manifest 为准**，测试即校验该哈希基线）。

## 未覆盖项与去向（区分四类证据口径）
- R03 修复的**在线终态**（真实 MySQL 重建后 `continuation_of_table_id` 悬空=0、行数/hash 一致）：单测以 Mapper 交互断言复现并锁死语义，DB 落库终态待 r4 实例（含本修复代码）在线核验，属"在线证据"缺口，非"自动化证据"缺口。
- `linkContinuations` 页序谓词修改未破坏存量断言已由 78/78 全量回归证实；主迭代 P1/P2 冒烟脚本（HTTP 链路）复跑归入整改收口阶段。

