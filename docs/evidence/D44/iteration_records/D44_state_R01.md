# D44_state_R01 —— RAG 阶段答辩与里程碑（one 仓）

- 迭代/切片：D44 / R01；冻结锚 commit：**498d48f**（父 d9b6f5e；任务书 ea024a5 系编制时态→RE-ANCHOR 裁决留痕 probe\baseline-recheck.txt）；本切片交付 commit：HEAD 单提交（信息 `D44 项目迭代R01 RAG阶段答辩与里程碑`，不 push）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / Spring Cloud 2025.0.0 / PG 18.7+pgvector 0.8.7 / MySQL 8 / Servlet Tomcat
- 冻结参数：datasetHash=75cc8d83cfb2…（20 题 MATCH）baseline=20×17 denyCode=4030 idAnchor=**de8d2f0c6236…6103**（D44 新锚）contentAnchor=**c377bb2f62d7b71b…64fb**（D43/D37 跨代恒等实证）retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3} regressionExemptCols={timing,recordedAt}（本轮实际全列 diff=0 强于口径）d41Audit=195/30/0/0 restartBudget=6（实耗 3）freezeManifest=352 main+35 test
- 偏差裁决：D1 冻结=manifest+drift=0 ✓ / D2 引用真实=三层结构断言+mock 边界声明 ✓ / D3 删除同步+FIX-004 回归 ✓ / D4 权限 335 格+A6 补口 ✓ / D5 拒答零模型调用 ✓ / D6 架构图实扫生成 ✓ / D7 可复现演示 9/9 ✓ / D8 优化清单 9+6 新 ✓ / D9 里程碑+失败案例 ✓ / D10 评测纪律继承 ✓（全部执行毕）

## 门禁与用例落表

| 编号 | 状态 | 证据路径（docs\evidence\D44\） | 备注 |
|---|---|---|---|
| KP1–KP4 | PASS | probe\kp1-endpoint-scan.txt / kp2-* / kp3-iteration-index.txt / defense-protocol.json | 端点 57 实扫；DDL 幂等×2；19/19 证据代齐；协议冻结 |
| C01 | PASS | git\mvn-test-final.txt（**115/115**, files=22, f0/e0/s0）+ frontend-test.txt（6/6） | 继承 110+CitationTruthDefense 5；骨架=498d48f |
| C02 | PASS | probe\（四门禁文件）+ freeze-manifest.json | 裁决全留痕 |
| C03 | PASS | freeze-manifest.json + **freeze-verify.json（387/387 SHA 复验 DRIFT=0）** | FIX-D44-001 修正后哈希入 manifest，无豁免漏网 |
| C04 | PASS | ddl\ddl-run2/run3 + probe\kp2-dual-anchor.txt + cleanup\post-cleanup-anchor.txt | 8 片段；双锚恒等恢复实证 |
| C05 | PASS | evaluation\run-2026-11-27T175423…（baseline.csv **21 行全列 diff=0**）+ p2-summary.json | 质量 1.0/1.0/1.0/0.0；四态 0/20/0/0 与 run-1810 同构；错峰纪律留痕 |
| C06 | PASS | verify\citation-1-layers-summary.json（服务 5/5+在线 PASS+数据 5/5 RECONCILED） | 模型级=NOT RUN 边界声明（非 FAIL） |
| C07 | PASS | verify\deletion-checks.json（**8/8** 含 FIX-004 回归 countMismatch=0） | 重复删除幂等+tableOrphans=0 |
| C08 | PASS | matrix\c08-summary.json（**335 格 MISMATCH=0 LEAK=0**）+ expired-probe.json（7/7）+ matrix-disabled-12.json（25 项零门禁） | 三轮定界史在册；审计 146→233 |
| C09 | PASS | verify\refusal-summary.json（**3 hard cells PASS**+R4 OBS） | 零模型调用=生成前短路+mock 计数；v2 口径（REFUSE 允许 evidenceCount>0） |
| C10 | PASS | architecture\architecture.md（全景+六域+权限代数+状态机 4 图） | graph TD/无重复 ID/Typora 兼容自检 |
| C11 | PASS | api-inventory.md（20 控制器×57 端点+ADMIN 22/PUBLIC 35+错误码表） | OBS-D44-X3/X4 契约注记；与 KP1 恒等 |
| C12 | PASS | data-dictionary.md（d44_ 五表+共享七表+PG 4 列，全实读） | 含 metadata.documentId camelCase 等硬事实 |
| C13 | PASS | demo\demo-transcript.json（**9/9 ALL-PASS**）+ temp_docs\D44\d44_demo.ps1 | S0 预清可重复运行；mock 零远程；跑后回冻结面 18 |
| C14 | PASS | failure-cases.md（15 条=12 bad-case+4 FIX+D44 新 OBS；六域 UNATTRIBUTED=0） | D-EX/R03 机理修正经 D41/D42 状态文件索引 |
| C15 | PASS | optimization-backlog.md（O1–O15=9 继承+6 新增；P1×3；🔒×8 标授权） | 须裁决项显式标注 |
| C16 | PASS | milestone-report.md + evaluation\report-2026-11-27.md（20/60 双口径+基线对照） | D25–D43 演进全景 20 代表 |
| C17 | PASS | regression\d41-cell-audit.json → evaluation\c17-cell-diff-rerun.json（**195/30/0/0 恒等**） | wrong_value=0 不回退 |
| C18 | PASS(口径内) | regression\rerank-smoke.json | 默认关零差异=C05 diff=0 行为级实证+源码+5 单测；**在线 opt-in 臂 NOT RUN**（归 O4） |
| C19 | PASS | cleanup\final-verification.txt（**FIXTURE_REMAINING=0** 多点：doc d44- 域 0/sync 矩阵 0/表格 0/0/pt 0/chat 本轮域 0/PG DEMO 0） | 双锚恢复；遗留零触碰；chat stamp800301 域 12+24 终清留痕；R03VERIFY-003 ACTIVE=11-24 前代原态勘误在册 |
| C20 | PASS | security\key-hygiene.txt（Bearer/sk- 零命中、HARDCODED=0、principals 未入库）+ cost\cost-actuals.json + git\（提交后核验） | 重启 3/≤6；DeepSeek 0/百炼 0/远程 rerank 0 |
| A1–A4 触发 | N/A | defense-protocol.json | 本轮无阻断缺陷触发（FIX-D44-001=测试资产非主链阻断，走 H2 裁决通道） |

## 分级结论

- L1（单测/纯函数）=PASS 115/115+前端 6；L2（服务级 HTTP 在线）=PASS 四能力+矩阵 335；L3（数据级双端对账）=PASS 5/5 RECONCILED+双锚；L4（跨组件评测回归）=PASS diff=0 全列+四态同构；**L5=NOT RUN（单机，跨机分布式失败面归 O8）**

## 答辩结论

冻结 drift=0（387 文件 SHA 复验）；四关键能力全绿（引用真实=三层结构断言+模型级边界声明；删除同步 8/8；权限 335 格 LEAK=0+A6 补口闭环；拒答 3 hard+零模型调用）；评测 20 题重跑与 D43 基线逐字节恒等；答辩四件套（架构/API/数据/演示）+里程碑/失败案例/优化清单全量落盘；d44_demo.ps1 一键复现 9/9。

## 成本实耗

DeepSeek calls=0 tokens=0（mock）；百炼=0；远程 rerank=0；重启 **3/≤6**；人工介入 1 次（FIX-D44-001 授权）；FIXTURE_REMAINING=0；双锚恢复恒等。

## 诚实声明

- **已执行并通过**：C01–C05、C07–C20 全表项；115 单测；演示 9/9；矩阵三轮+armO+expired+disabled；C17 恒等；终清复验。
- **已执行但失败（已闭环）**：C09 v1 4/4 FAIL=断言口径错（v2 修正 3 PASS 落盘，非产品缺陷）；矩阵轮 1 MM=44/轮 2 MM=3 定界史在册；demo 前两轮 FAIL 修毕（S0/S9 设计）；OBS-D44-X1 复活轮询 180s×2 败（转 DB 通道）。
- **尚未执行（NOT RUN，禁冒充）**：模型级引用真实性验证、真实模型臂评测、模型级故障注入、hybrid/rerank 在线 opt-in 臂、L5 跨机——全部列 O1–O4/O8 并标授权需求。
- **N/A**：A1–A4 阻断触发（未发生）；D41 前代表格域视图空=H6 隔离正确形态非缺陷。

## 遗留与后续（optimization-backlog.md 索引）

O1 质量型基线演进🔒 / O2 测试库隔离(P1)🔒 / O3 真实模型臂🔒 / O4 hybrid+rerank 组合臂 / O5 弱相关新数据集🔒 / O6 cross-encoder / O7 sync 归档🔒 / O8 跨机 L5 / O9 seeding activate+停机🔒 / O10 删除闭环登记域🔒 / O11 documents DELETE 端点🔒 / O12 拒绝面机器码🔒 / O13 tenant 回填🔒 / O14 夹具清单生成化 / O15 D41 30 unaligned 勘误注。
