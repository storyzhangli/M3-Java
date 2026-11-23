# D40 C01 单测与前端零回归证据（2026-11-23）

## 执行一：Maven Surefire 全量单测

命令（模块根 `D40\D40_DUDACR`，JAVA_HOME=JDK21）：

```
mvn.cmd -B test
```

结果：**BUILD SUCCESS，Tests run: 37, Failures: 0, Errors: 0, Skipped: 0**

| 测试类 | 数 | 覆盖冻结项 |
|---|---|---|
| SyncOperationAndReasonCodeTest | 8 | KP4 归一化（DELETE→version 0）/fromName 容错/classify 连接族→PG_UNREACHABLE、哨兵 SIMULATED_PG_CONNECTION_LOST→PG_UNREACHABLE、非连接→PG_DELETE_FAILED 兜底、cause 链终止 guard |
| DocumentSyncServiceStateMachineTest | 8 | 三态全迁移：markPending 首见 0 ∧ 重复保留 attempt（根因修复回归哨兵）；markFailed attempt+1 ∧ 达 max 改写 RETRY_EXHAUSTED ∧ 退避落库；claimForRetry 手动耗尽重置 0 / 自动保留 / 非 FAILED no-op（先捕获后清空顺序 bug 哨兵）；markSynced 终态清原因码；UPDATE_CLEANUP 自然键携版本号 |
| SyncRetryRunnerPipelineTest | 5 | DELETE→deleteByDocument / UPDATE_CLEANUP→retryCleanupVersion 路由；未知 operation fail-closed；管线再失败异常不外溢且视图如实 FAILED；claim 竞争失败跳过 |
| AdminAuditServiceTest | 3 | 审计 null 归一空串/0（NOT NULL 零违约）、ALLOWED 全字段、insert 抛错不阻断业务（旁路面裁决实证） |
| ReconciliationReportViewDriftSummaryTest | 3 | driftSummary 计数：VECTOR_ONLY=orphan−STALE 子集、归零断言口径、Issue 4/5 参构造兼容 |
| D40SyncControllerConsistencyTest | 6 | 非 dryRun 空 prefix→4001 拒绝（reconcile 零调用）；dryRun 默认直通；prefix 透传 reconcile(false,"d40-")；enabled+USER→AccessDenied(ADMIN_REQUIRED) ∧ DENIED 审计；ADMIN→ALLOWED 审计后放行；disabled→零门禁零审计（H1/C14 disabled 分支） |
| FaultInjectingVectorStoreTest | 4 | 第 N 次 add 消耗式仅抛一次；哨兵 message 冻结；0=禁用全透传（回归态等价直通）；add/delete 计数独立 |

## 执行二：前端安全测试零回归

命令：`node --test front-end-safety.test.js`（D40 前端目录）

结果：**tests 6 / pass 6 / fail 0**（骨架既有测试，本轮未触碰前端文件）

## 过程中发现并修复的真实缺陷（诚实记录）

1. **markPending 归零 attempt 破坏耗尽判定（根因修复）**：`UPDATE_CLEANUP` 重试入口
   `retryCleanupVersion` 复用 `markPending`，原实现 upsert 恒写 `attempt_count=0` →
   自动调度下 attempt 永远回 1，`RETRY_EXHAUSTED` 不可达、熔断待人工语义失效。
   修复为保留行内既有 attempt（首见=0）；唯一归零入口保留为手动 claim 耗尽行（KP4 裁决 2）。
   回归哨兵=`markPendingPreservesAttempt` 第二段断言。
2. 测试适配两处（非产品缺陷）：`BaseMapper.updateById` 重载歧义需显式类型匹配；
   `ResultCode.SUCCESS` 实际码值=200（断言按真实契约修正，非改产品）。

## 判定

**C01 = PASS**（37 Java 单测 + 6 前端测试全绿；测试仅 mock 边界，零外部调用，零成本合规）。

---

## 口径沿革说明（R03 整改追加，2026-11-23 晚）

本文件记录的是 **P4 时点首版 C01**（37/37）。后续口径演进如下，最终以
`final-test-summary.md` + `surefire-summary.txt` 为准：

| 时点 | `mvn -B test` 结果 | 差异原因 |
|---|---|---|
| P4 首版（本文件原文） | **37/37** | 7 个测试类基线 |
| P5（C17 引用交叉） | **42/42** | 新增 CitationDeletedClassificationTest 5 case |
| R01/R02 整改后 | **53/53** | 再新增 DocumentSyncServiceAtomicClaimTest 6（原子领取）+ RecoverStuckTasksScopeTest 5（范围隔离） |

37→42→53 每一步数字均可在 `target\surefire-reports\` 原始报告核对
（整改后快照已复制至本目录 `surefire-summary.txt`），非人工修改数字。
`D40_state_R01.md` 记录 42/42 对应 P5 收官时点，与本文件 P4 记录不矛盾，
统一以 final-test-summary.md 为整改后权威口径。
