# D40 最终测试汇总（R03 整改后权威口径，2026-11-23）

## 1. 单元测试（最终态）

命令（模块根 `D40\D40_DUDACR`，JAVA_HOME=JDK21，真实退出码 0，BUILD SUCCESS）：

```
mvn.cmd -B test
```

**Tests run: 53, Failures: 0, Errors: 0, Skipped: 0**

| 测试类 | 数 | 归属 |
|---|---:|---|
| SyncOperationAndReasonCodeTest | 8 | D40 P1（KP4 冻结） |
| DocumentSyncServiceStateMachineTest | 8 | D40 P1（R02 适配后 3 个 claim 用例改断言条件 UPDATE 参数与 never(updateById)，用例数不变） |
| SyncRetryRunnerPipelineTest | 5 | D40 P1 |
| AdminAuditServiceTest | 3 | D40 P1 |
| ReconciliationReportViewDriftSummaryTest | 3 | D40 P4 |
| D40SyncControllerConsistencyTest | 6 | D40 P3 |
| FaultInjectingVectorStoreTest | 4 | D40 P2 |
| CitationDeletedClassificationTest | 5 | D40 P5（C17） |
| **DocumentSyncServiceAtomicClaimTest（整改新增）** | **6** | R02：双线程竞争唯一胜者/affected=0→null/非 FAILED 不发放行/id 缺失防御/水位参数传递/失败方零管线动作/成功视图回显 |
| **RecoverStuckTasksScopeTest（整改新增）** | **5** | R01：SQL 层 likeRight 断言/无参+空白 prefix 全量兼容/范围外行零触达防御线/applyCompensation scope 透传/scope 空串映射全量 |

逐类原始行见同目录 `surefire-summary.txt`（自 `target\surefire-reports\` 直接摘录，非人工统计）。

### 37 → 42 → 53 历史关系

| 时点 | 数 | 事件 |
|---|---:|---|
| P4 首版 C01 | 37 | `c01-unittest.md` 原文（保留未改，已追加沿革标注） |
| P5 收官 | 42 | +CitationDeletedClassificationTest 5（C17 引用交叉） |
| R01/R02 整改后 | 53 | +AtomicClaim 6 +RecoverScope 5 |

无删除测试、无降低断言、无跳过；每个新数字可由 surefire 报告复算。

## 2. 前端零回归

`node --test front-end-safety.test.js`：tests 6 / pass 6 / fail 0（R03 轮未触碰前端文件，沿用 C01 记录）。

## 3. 命令与可复现性

```powershell
$env:JAVA_HOME='D:\SoftwareInstall\JDK\JDK21'
cd E:\AI\project\AI-Learning-one\M3-Java\D40\D40_DUDACR
& 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd' -B test
```
