# D40 回归最终报告（R03 整改，2026-11-23）

## 1. 最终态结果（R01/R02 代码修复后重跑，r6 默认实例）

| 步骤 | 结果 | 退出码 |
|---|---|---:|
| STEP0 匿名行为探针 | code=200 total=3 top1=3b1501c0-6a99-535a-a787-991f877024c8（=冻结值） | 0 |
| STEP1 评测器源码只读编译 | 通过 | 0 |
| STEP2 SelfTest | 75/75 全绿 | 0 |
| STEP3 完整评测 20 问×3 轮+warmup 3 | 完成（run-2026-11-23T231959-…-PGVECTOR） | **1** |
| 轮间一致性检测 | 无 REPEAT_RESULT_DIVERGED（否则脚本熔断 exit 3） | — |
| STEP4 与 D37 冻结基线质量投影 diff | recordsA=60 recordsB=60 **QUALITY_FIELDS_IDENTICAL** | 0 |
| STEP5 QA-001 锚点 | top1 同 id，similarity delta=0 ≤ 1e-9 | 0 |
| **判定** | **PASS（C05 零差异，修复后保持）** | — |

## 2. `runExit=1` 根因分析（任务书 R03 要求逐条回应）

1. **产生退出码 1 的具体命令**：STEP3 `java d37eval.Evaluator --config … --rounds 3 --warmup 3`。
2. **对应日志**：`regression\run-evaluator.log`（末行 `[DONE] runDir=… exitCode=1`）+ run 目录 `summary.md` 四态计数。
3. **性质判定——预期非零退出，非真实失败、非脚本包装错误**。源码契约（`docs/temp_docs/D37/evaluator/src/d37eval/Evaluator.java` L648-651，只读引用）：

   ```java
   if (fail > 0) { return 1; }
   return pass > 0 ? 0 : 2;
   ```

   即 `1` = 数据集业务判定存在 FAIL 行。D37 冻结基线本身含 FAIL（QA-001
   REFUSAL_FALSE_NEGATIVE 为基线冻结时点已确认的业务事实），D38/D39 两轮回归
   `runExit=1` 同值先例（`D39\regression\regression-result.json.runExitNote`）。
   执行失败路径为 exit 2（PASS=0）与 REPEAT_RESULT_DIVERGED 分支——两者均未出现。
4. **`diffExitVsD37=0` 的证明边界**：它只证明本轮质量投影与冻结基线逐字节一致，
   不独立证明前置命令"成功"。本门禁以组合链判定：SelfTest 全绿 ∧ 三轮零发散
   ∧ diff=0 ∧ QA-001 锚点达标 ∧ runExit ∈ {0,1}（脚本 STEP3 显式白名单，
   2/其他值直接 exit 1 失败）——runExit=1 落入预期语义集合而非被忽略。
5. **未因 diff=0 忽略前置命令**：脚本对 runExit 的处理是先做集合校验（∈{0,1}）
   再继续；本次整改把该契约文字化写入 `regression-result.json` 新增字段
   `runExitNote` 与 `runExitContractSource`（可机读），不再只存在于脚本逻辑中。
6. **未修改任何退出码**：Evaluator 源码零触碰（只读复用），脚本 exit 语义未动。

## 3. 旧产物处置

`regression-result.json` 已由本次重跑覆盖（含 note 字段）；上一轮（18:29，r5）
的 run 目录保留在 `regression\run-2026-11-23T1829*` 作为整改前对照，两轮 diff
结论一致（均 QUALITY_FIELDS_IDENTICAL）。未新建 final-regression-result.json
冗余副本（沿用项目现有 `regression-result.json` 命名，任务书 5.5 允许）。

## 4. 回归矩阵（任务书 §6 十一项对照）

| 类别 | 本轮证据 | 状态 |
|---|---|---|
| 原有更新功能 | mvn test 53 全绿（状态机/闭环用例）+ 本文 §1 | PASS |
| 原有删除功能 | 同上（delete 路由 5 用例） | PASS |
| 解析失败不切版本 | `exceptions\e1-parse.json`（P5 事实，代码未触碰该路径） | PASS |
| 部分向量入库失败可追踪恢复 | `exceptions\e2-e3-instance-a.json` + 新增 scope 用例 | PASS |
| 删除失败重试收敛 | `delete\retry-converge.json` + R02 原子领取双层实证 | PASS |
| 重复事件幂等 | `exceptions\p5-matrix.json`（E4） | PASS |
| 旧版本残留一致补偿 | `consistency\repair-recheck.json`/`c12b-*` | PASS |
| 范围隔离 | `repair\D40-R01-scope-isolation-realdb.json` + `D40-R01-online-probe.json` + 单测 5 case | PASS（整改新增） |
| 并发领取 | `repair\D40-R02-atomic-claim-realdb.json`（真实 MySQL 双线程 winners=1）+ 单测 6 case | PASS（整改新增） |
| 最大重试次数 | realdb testD（attempt≥max 被水位排除）+ 单测 D | PASS |
| D37 基线回归 | 本文 §1（QUALITY_FIELDS_IDENTICAL 60/60，基线未改） | PASS |
