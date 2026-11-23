# D40-R01 整改总结 —— 文档更新删除与一致性修复（R01/R02/R03）

- 整改基线：骨架 `f3cf475` + 主迭代 `2f2af95`（`git log` 复核一致）
- 任务书：`E:\AI\document\learning\M3-Java\docs\proj_docs\D40-R01：文档更新删除与一致性修复整改任务书.md`
- 整改提交：`fix(D40): enforce reconciliation scope and atomic retry claim`（hash 见 `git log -1`，不 push）
- 流程：先扫描判定（任务书 2.1 强制），属实才改；全程 mock、零密钥入盘、D37 基线零触碰

## 1. 属实性判定（扫描实码证据）

| 问题 | 判定 | 实码证据 |
|---|---|---|
| R01 一致性修复范围未隔离 | **属实** | `DocumentVectorIndexService.recoverStuckTasks()`（L529）无参全表扫 `document_vector_index`（Dxx 共享表）PENDING/INDEXING∧超时行；`VectorReconciliationService.applyCompensation` L325 有 scope 形参且其余补偿段全部 `inScope()`，唯独 L371 无参调用 recoverStuckTasks → 跨域恢复风险真实存在 |
| R02 FAILED 领取非原子 | **属实** | `DocumentSyncService.claimForRetry`（L116）= 内存判 FAILED → 改字段 → `updateById` 无条件覆写，无 WHERE 状态守卫；check-then-act 竞态，多实例双 claim 双成功、attempt 双计数 |
| R03 测试证据口径不一致 | **属实** | `c01-unittest.md` 记 37/37（P4 时点）无被取代标注 vs 状态文件 42/42（P5 后）；D40 `regression-result.json` 缺 `runExitNote`（D38/D39 同型脚本产物均有，主迭代写脚本时丢失）；`runExit=1` 未解释 |

### 与任务书设计示意的差异（按 2.1"以实码为准"记录，未照抄示意）

1. 示意 SQL 含 `attempt_count+1`：与本项目冻结语义冲突（递增点=markFailed，claim 不自增，防双重计数）。整改保持原递增点，claim 仅做状态翻转+水位字段清理。
2. 示意测试 C 假想 INDEXING/DELETED 态在 `d40_document_sync` 不存在（KP4 三态冻结），改用该表真实状态构造用例。
3. 在线探针首次误用 `/vector-index/reconcile` 端点+service 形参名 `repairDocumentPrefix` 作 JSON 键——真实请求契约是 `/api/v1/sync/consistency` + `ConsistencyCheckRequest.documentPrefix`；已修正复跑并在证据文件留痕（首版结论作废覆盖）。

## 2. 修复内容

### R01 范围隔离（2 文件）

- `DocumentVectorIndexService.recoverStuckTasks(String documentPrefix)` 重载：QueryWrapper 追加 `likeRight("document_id", prefix)`（SQL 生成 `document_id LIKE ?`，参数 `d40-%`）+ 循环内执行前 `startsWith` 防御终校验（范围外行 warn+continue，快照都不读）；无参版委托 `recoverStuckTasks(null)` 保持全量兼容。
- `VectorReconciliationService.applyCompensation`：恢复段改传 `scope.isEmpty() ? null : scope`，与其余补偿段同受 scope 约束。

### R02 原子领取（2 文件，SyncRetryRunner 零改动）

- `DocumentSyncMapper.claimFailedAtomically(id, newAttemptCount, reasonCode, now)`：`UPDATE … WHERE id=? AND sync_status='FAILED'` 条件 CAS。
- `DocumentSyncService.claimForRetry` 重写：`row.getId()==null` 防御 → 条件 UPDATE → `affected!=1` 返回 null（让位）→ 成功才回显内存视图；手动∧RETRY_EXHAUSTED 经 newAttempt=0 参数承载；`updateById` 不再出现在领取路径。既有 `claimed==null→skip` 路由天然兼容败者。

### R03 口径统一（证据侧，零产品代码）

- `c01-unittest.md` 追加沿革表（37/37 P4 → 42/42 P5 → 53/53 整改），不改写历史原文；新增 `unittest\final-test-summary.md`（53/53 权威口径，逐类数字来自 surefire）与 `unittest\surefire-summary.txt`（原始报告摘录）。
- `d40_c05-regression.ps1` 产物补 `runExitNote`/`runExitContractSource` 字段；`regression\final-regression-report.md` 全文解释 runExit=1。
- **runExit=1 根因**（Evaluator.java L648-651 源码契约，只读引用）：`if (fail>0) return 1; return pass>0?0:2;`——1=冻结基线含已知业务 FAIL 行（QA-001 REFUSAL_FALSE_NEGATIVE），非执行失败；执行失败路径为 2/REPEAT_RESULT_DIVERGED（均未出现）。D38/D39 同口径先例。

## 3. 验证矩阵（全部真实执行）

| 层 | 动作 | 结果 |
|---|---|---|
| 单测 | `mvn -B test`（JDK21，模块 D40_DUDACR） | **53/53**，Failures 0，EXIT=0，BUILD SUCCESS；新增 AtomicClaimTest 6 + RecoverScopeTest 5，适配 StateMachineTest 3（不删不降） |
| 真实 MySQL（R02） | `D40AtomicClaimProbe`（双独立 Connection 双线程同刻竞争同一 FAILED 行） | affected=[0,1]、winners=1、final attempt=2 不变、reclaim=0、SYNCED/不存在 id=0、attempt≥max 水位排除、fixtures_cleaned=5 → **PASS**（`repair\D40-R02-atomic-claim-realdb.json`） |
| 真实 MySQL（R01） | `D40RecoverScopeProbe`（共享表 4 夹具行 d40-r01×2/d39-r01/d41-r01） | scoped 恰命中 2、全量命中 4、UPDATE 仅触 2、越界行全字段指纹不变、cleaned=4 → **PASS**（`repair\D40-R01-scope-isolation-realdb.json`） |
| 在线契约 | r6（整改代码实例）`POST /api/v1/sync/consistency` `{"dryRun":true,"documentPrefix":"d40-"}` | code=200、scopePrefix 回显='d40-'、空 prefix 对照回显=''；全域 driftMetaOnly=24（域外历史差异）恰为 R01 必要性的在线实证 → **PASS**（`repair\D40-R01-online-probe.json`） |
| 回归重跑 | r6 上 `d40_c05-regression.ps1` 全链 | SelfTest 75/75 → 三轮零发散 → diff **60/60 QUALITY_FIELDS_IDENTICAL** → QA-001 delta=0；新 run 目录 `run-2026-11-23T231959-…-PGVECTOR`，`regression-result.json` 带注记覆盖 → **PASS**（`regression\final-regression-report.md`） |

验证边界（任务书 4.4 口径）：Mockito 单测锁语义路由，不描述为真实库并发测试；原子性本体由真实 MySQL 双线程实证。"生产多进程/多实例部署下的调度互斥"仍未演练（遗留项，水位+CAS 条件已具备）。

## 4. 交付物清单

- 产品代码 4 文件：`DocumentVectorIndexService.java` / `VectorReconciliationService.java` / `DocumentSyncMapper.java` / `DocumentSyncService.java`
- 测试 3 文件：新增 `DocumentSyncServiceAtomicClaimTest.java`、`RecoverStuckTasksScopeTest.java`；适配 `DocumentSyncServiceStateMachineTest.java`
- 证据：`repair\`（3 JSON）+ `unittest\`（final-test-summary.md、surefire-summary.txt、c01 沿革）+ `regression\`（final-regression-report.md、带注记 regression-result.json、新 run 目录、diff/selftest/evaluator 日志）
- 工具：`docs\temp_docs\D40\probe\tools\D40AtomicClaimProbe.java`、`D40RecoverScopeProbe.java`（不入 Git 的 .class 已撤）；`d40_c05-regression.ps1`（runExitNote 字段）
- 文档：D40-01 §3.1/§3.4/§6.3/§9 增补；D40-02 §3.6/§4.3/§5/§6(Q11/Q12)/记忆简语/层级树增补；`D40_state_R01.md` 整改段
- 环境收尾：r6 已停、PORT_FREE 复核通过；夹具探针自清理（5+4 行）

## 5. 踩坑记录（工程留痕）

1. javadoc 内 `DOC-*/d32eval` 的 `*/` 提前闭合块注释 → 编译错误，改写"DOC 前缀 / d32eval"。
2. 在线探针请求体键名=方法形参名假设错误（真实契约看 DTO 字段，不看 service 签名）。
3. `git add -f` temp_docs 会带入 `.class`，必须 reset 撤出后复验（主迭代教训沿用，本轮已执行）。
4. Evaluator 退出码不能按"非 0 即失败"解读——先读源码契约再定性。
