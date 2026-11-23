# D40_state_R01 —— 文档更新删除与一致性修复（DUDACR）
- 迭代/切片：D40 / R01；基线 commit：f3cf475（执行时 HEAD 复核一致）；本切片交付 commit=本次提交（含本文件，hash 见 `git log -1`，不 push）
- 仓库/模块：one 线 `E:\AI\project\AI-Learning-one\M3-Java` / `D40\D40_DUDACR`（com.zl.d40，端口 8080）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / MySQL 8.0.33 / PG 18.7+pgvector 0.8.7
- 冻结参数：baseFingerprint=10fc69d5c9c3fd088b98578d74278135b1c04b7cd8b29b361b4c983926567af9；sync 键=documentId×operation×version；原因码 KP4 冻结 6 项；denyCode=4030；batchSize=100（PARTIAL 验证需 1）；retry 调度 fixedDelay 默认 60s；幂等三元组=payloadHash+chunkId 集+关联数（D31 冻结，修复走 forceReindex 旁路）（probe\sync-params.json、kp1-kp4-adjudication.md 含 KP5 裁决）
- 偏差裁决：KP1 endpoint 分级表 ✓ / KP2 夹具域表隔离+前缀清理 ✓ / KP3 双端确认口径 ✓ / KP4 原因码边界（索引侧 3 码+PARSE_FAILED 不并入防双实现）✓ / KP5（追加）门禁两层结构=filter 拒匿名不留 guard 审计，按设计语义留痕 ✓
- 实现偏差（相对任务书编制值，均实读为准）：C06 证据文件名=update\quad-f-vs-f2.json（任务书示例 flow-v1v2.json 未采用，quad 四元组即顺序实证）；C08 证据=delete\retry-converge.json+矩阵留痕（无独立 closure.json，闭环全链在该两件内）；import 前置依赖 register（任务书未显式写明，实测 4001 负路径留痕）。

## 门禁与用例落表（四态，合计 20+KP 条）
| 编号 | 状态 | 证据路径（docs\evidence\D40\） | 备注 |
|---|---|---|---|
| KP1–KP5 | PASS | probe\{endpoint-inventory.csv,kp2-inventory*.json,sync-params.json,kp1-kp4-adjudication.md} | seeding 后指纹逐字节=冻结值；遗留行清单冻结 |
| C01 | PASS | unittest\c01-unittest.md | mvn -B test 42/42（缺陷修复后复跑）；node --test 6/6 零回归 |
| C02 | PASS | probe\ | 同 KP 行 |
| C03 | PASS | ddl\{c03-ddl-idempotent.md,ddl-seed.log} | 两表 DDL ×2 重跑零报错零数据变化 |
| C04 | PASS | probe\kp2-inventory-postseed.json | 8 片段；指纹 10fc69d5… |
| C05 | PASS | regression\{regression-result.json,diff-vs-d37-frozen.txt,selftest.log,run-evaluator.log,run-*\} | QUALITY_FIELDS_IDENTICAL 60/60；SelfTest 75/75；QA-001 top1 同 id delta=0；匿名探针零差异；前置=先 C19 清理（对齐 D39 口径，两序留痕于 c19-summary） |
| C06 | PASS | update\quad-f-vs-f2.json | 先写→SWITCH→清旧顺序四元组实证 |
| C07 | PASS | update\reentry-fingerprint.json | 中途失败重跑与一次成功逐字节一致（期间暴露并修复缺陷 1 forceReindex） |
| C08 | PASS | delete\retry-converge.json + exceptions\p5-matrix.json | tombstone 保留、双端确认、SYNCED |
| C09 | PASS | delete\retry-converge.json + exceptions\e2-e3-instance-a.json | 注入→FAILED+PG_UNREACHABLE→retry→SYNCED 0/0 |
| C10 | PASS | exceptions\p5-matrix.json | SYNCED no-op/重复 register 4001 |
| C11 | PASS | update\cleanup-sync.json | cleanupSuperseded 失败→UPDATE_CLEANUP FAILED→重试 SYNCED 残留归零 |
| C12 | PASS | consistency\{detect-report.json,c12b-*.json} | 五类全命中；C12b DB 级构造暴露缺陷 2（守卫复用 0 行静默）→setVersionPointer 修复后 r4 全域归零复验 |
| C13 | PASS | consistency\{repair-recheck.json,c12-c13-summary.md} | 修复走管线+grep 零裸 SQL 证明；检测全域/scope 限修复（设计裁决） |
| C14 | PASS | security\gating.jsonl（14 探针）+ regression\（disabled 侧） | 匿名 5+userA1 3 全 4030；adminA1 业务码放行；读面语义如实记录 |
| C15 | PASS | security\audit-trail.json | 7 行 ALLOWED=4/DENIED=3；actor/role/action/target/result/reason_code/request_id 零缺失；匿名 filter 层无 guard 审计=设计语义（KP5） |
| C16 | PASS | exceptions\e1-parse.json | FAILED+PARSE_FAILED、PG delta=0、零残留、lifecycle=ACTIVE 如实记录 |
| C17 | PASS | exceptions\citation-deleted.json | removed=CITATION_TARGET_DELETED；竞态结构性 N/A 留痕；单测 5 case 入 42 |
| C18 | PASS | exceptions\injection-containment.json | 默认 false、主 profile 不装配、回归态无残留 |
| C19 | PASS | cleanup\{c19-summary.json,cleanup-dryrun/apply/verify.json,postclean-inventory.json} | FIXTURE_REMAINING=0（kd12/sync16/audit7→0）；DOC-* 3/chunk_other 32/pg8 零触碰；指纹恢复；principal 4 行基础设施保留标注 |
| C20 | PASS | iteration_records\C20-hygiene-cost.json | sk-/Bearer/token 证据树 0 命中+token 实值内存比对 0 泄露；check-ignore 命中 fixture-tokens；REMOTE_HITS=0 |
| L5 | NOT RUN | — | 单机环境，如实标注 |

## 分级结论
L1=PASS（C01）L2=PASS（C02–C05）L3=PASS（C06–C18）L4=PASS（C19–C20）L5=NOT RUN（单机）

## 产品缺陷与修复（本迭代实测暴露，2 项）
1. **幂等三元组盲区**：DRIFT_META_ONLY 修复被 D31 冻结短路（关联在/PG 无时 indexChunks no-op）→ `indexChunks` 增 `forceReindex` 重载，`VectorReconciliationService` 修复路径传 true；普通入库路径零行为变化（C05 佐证）。
2. **守卫复用静默 0 行**：一致性 repoint 复用 `bumpVersionPointer` 的 `WHERE version<target`，回拨场景永不生效 → 新增 `setVersionPointer`（权威置位），`VectorIndexStateTx.repointVersionPointer` 改调；前进/置位两意图显式分离。
修复后：`mvn -B test` 42/42、C12b 五类复验归零、C05 零差异，均重跑实证。

## 成本实耗
DeepSeek calls=0 tokens=0（AI_MODE=mock 全程，运行日志 REMOTE_HITS=0）；百炼 calls=0（未启用）；向量全部本地哈希 Embedding 384 维；夹具清理 FIXTURE_REMAINING=0。

## 诚实声明

- 已执行并通过：上表全部 PASS（每条指向实测证据文件）；`mvn -B test`（42/42）与 `node --test`（6/6）真实退出码 0；D37 评测器全量 20 问×3 轮+SelfTest 75/75；五类漂移构造-检测-修复-复查全链真实执行；14 探针门禁矩阵真实执行；C19 harness before/after 实数。
- 已执行但失败（均修复或留痕，无带病项）：产品缺陷 2 项（上节）；脚本类——gating 探针 token 引用路径（.token 漏级）、D40AuditDump 列名对齐 DDL、cleanup 脚本 D40Probe 传参误用（改 stdout 重定向）+类未编译、Out-File 目录未建、E1 矩阵首跑漏 register 前置（负路径留痕）——均测试/治理脚本缺陷，非产品断言失败。
- 尚未执行（建议后续验证）：真实模型态端到端（本迭代纯 mock）；DDL 在生产 PG 实例的迁移演练；SYNCED 行长期归档策略。
- N/A 项：删除竞态（结构不可达，C17 留痕）；L5 跨机；分布式并发多实例 retry 互斥（本地单实例，水位条件已具备但未双实例演练）。
- 遗留风险与 D41 候选：filter 层匿名拒绝审计（当前设计语义）；IdP/RBAC 真实接入时 token 生命周期；多实例部署下 sync 行乐观锁/领取语义（claimed 残留路径 SyncRetryResultView 已如实呈现）；knowledge_document.version 权威与 PG metadata version 冗余键的单向同步文档化；d40_document_sync SYNCED 行归档。

## R01 整改段（2026-11-23，本文件增补，追加于诚实声明之后）

- 整改基线=f3cf475+2f2af95；三问题扫描判定**全部属实**（实码证据见 `iteration_records\D40_R01_repair-summary.md` §1）；整改提交=`fix(D40): enforce reconciliation scope and atomic retry claim`（hash 见 `git log -1`，不 push）。
- **R01 范围隔离**：`recoverStuckTasks(String documentPrefix)` 重载（SQL likeRight+行内 startsWith 双层）+ `applyCompensation` 恢复段透传 scope；无参=全量兼容不变。证据：`repair\D40-R01-scope-isolation-realdb.json`（真实库 4 夹具行，越界行零触达）+ `repair\D40-R01-online-probe.json`（scopePrefix 回显契约；全域 driftMetaOnly=24 为跨域误修复风险的在线实证）。
- **R02 原子领取**：`claimFailedAtomically` 条件 UPDATE（`WHERE id=? AND sync_status='FAILED'`，affected≠1→null 让位）；attempt 递增点冻结在 markFailed 不变；SyncRetryRunner 零改动。证据：`repair\D40-R02-atomic-claim-realdb.json`（真实 MySQL 双连接双线程 winners=1、attempt 不双增）。
- **R03 口径统一**：单测权威口径 **53/53**（沿革 37→42→53 注记于 `unittest\c01-unittest.md`，逐类数字 `unittest\surefire-summary.txt` + `unittest\final-test-summary.md`）；C05 重跑零差异（新 run `run-2026-11-23T231959-…`，diff 60/60 IDENTICAL，`regression-result.json` 补 runExitNote）；**runExit=1=冻结基线已知业务 FAIL**（Evaluator.java L648-651 契约 `fail>0→1`，非执行失败；分析全文 `regression\final-regression-report.md`）。
- 口径修订：上文 C01 行与诚实声明中"mvn -B test（42/42）"为主迭代时点事实（保留不改写历史）；**自整改起权威口径为 53/53**（final-test-summary.md）。其余 PASS 行不受影响（R01/R02 未改变既有业务语义路径，C05 零差异佐证）。
- 遗留更新：上文 N/A 项"分布式并发多实例 retry 互斥"部分升级——领取互斥原语已获真实 MySQL 双连接实证，**生产多进程调度互斥**仍待真实多实例演练；在线请求契约提醒：带修复域端点=`/api/v1/sync/consistency`，字段=`documentPrefix`（`/vector-index/reconcile` 仅 dryRun=全量语义）。
