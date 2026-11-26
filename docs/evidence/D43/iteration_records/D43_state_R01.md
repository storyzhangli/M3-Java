# D43_state_R01 —— RAG 端到端、权限与回归（one 仓）
- 迭代/切片：D43 / R01；骨架基线 commit：c47be85（执行时复核一致）；本切片交付 commit=HEAD 单提交（父=c47be85，`git log -1 --format=%H` 可取；不在本文件写死 hash 以避免 amend 自指悖论；157 files +17276/−18，不 push；logs\D43 *.log/*.err 按 repo 规则不入库）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / PG 18.7+pgvector 0.8.7 / MySQL 8
- 冻结参数：datasetHash=75cc8d83cfb2…（20 题）baseline=20×17 denyCode=4030 idAnchor=50c457b6762a…（终核恒等） contentAnchor=c377bb2f62d7…（V2 口径=c377bb2f62d7b71b4dfa3f87d47722b7bcdc989aaf4b218c607f8e7a9b6d64fb 等价实证） retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3} blockingCriteria=KP4 冻结 primaryDomainRule=权限>解析>切分>Embedding>检索>生成 regressionExemptCols={timing,recordedAt} d41Audit=195/30/0/0
- 偏差裁决：D1–D10 全部执行时确认无偏离（D9 双锚+R03 复验、D2 二分法为关键生效项）

## 门禁与用例落表

| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | PASS | probe\、e2e-protocol.json | 六域映射/存储/冻结资产/协议全冻结 |
| C01 | PASS | regression\mvn-test-final.log(+surefire) | 继承 99→100，终态 110/110 FAIL=0 |
| C02–C04 | PASS | probe\kp* | 契约/DDL 幂等/双锚+SHA |
| C05 | PASS | evaluation\run-1810\ + c05-quality-diff-rerun2.txt | diff=0（经 run-1757=39/run-1801=20 两次定界） |
| C06–C08 | PASS | e2e\lifecycle-run5 49/49 | 链节×双态矩阵 |
| C09 | PASS | run-1810 metrics | 3 轮等分布 17/10/2 |
| C10/C11 | PASS | matrix\pre/post/post-armo | 30 缺陷格→0；LEAK=0 |
| C12 | PASS(边界声明) | BC-D43-009 + metrics denominators | mock 态 sources 恒空=可测性边界；citations=0 不误放行已证 |
| C13 | PASS | fixes\fix-registry.jsonl + fix-001..004.md | 4 阻断 FIXED-VERIFIED；FIX-005→OBS-D43-X3 更正 |
| C14 | PASS | exceptions\run4 7/7 | A4 双态含 FAULT 臂 |
| C15 | PASS | matrix\c15-denied-audit-dump.txt | 12/18/6 精确一致 |
| C16 | PASS | regression\d41-cell-audit.json | 195/30/0/0 不回退 |
| C17 | PASS | 卫生扫描会话留痕+gitignore L40 | sk-/Bearer 零命中；token 明文仅 temp_docs(忽略) |
| C18 | PASS | regression\c18-fp-final.txt + cleanup\final-clean.txt | 双锚恒等；A 族清零；legacy/sync 正当行保留 |
| C19 | PASS | e2e-report.md | 三维矩阵报告 |
| C20 | PASS | git（本文件所在单提交） | 单提交不 push；staged 密钥扫描仅假 token 常量命中（H9 豁免） |
| A1–A6 | PASS×7 | exceptions\exceptions-run4.log | 缺口=A6 过期 token 未播夹具 |

## 分级结论
L1=单测/静态 PASS；L2=模块 HTTP 集成 PASS；L3=跨存储一致性（三投影）PASS；L4=端到端评测 PASS（diff=0）；L5=NOT RUN（单机 N/A）

## 端到端与分类结论
- 生命周期 49/49、越权 335 格（285+50）零泄漏；bad-case 12 条：质量型 3 族（17/10/2 题）、阻断已修 4、流程/资产/环境 5；UNATTRIBUTED=0

## 成本实耗
DeepSeek calls=0 / 百炼 0 / 远程 rerank 0；重启 8/8；mvnTest 预算内；夹具 FIXTURE_REMAINING=0（D43-A*/e2e 全清，legacy d32eval 24 行非本轮资产不触碰）；双锚恢复基线

## 诚实声明
- 已执行并通过：上表全部 PASS 项（含 C05 三段定界、终测 110/110 surefire 复核）
- 已执行但失败（先红复现器，均已闭环）：C01 继承 99/100（FIX-001）、pre 矩阵 30 缺陷格（FIX-002/003）、L4 COUNT_MISMATCH（FIX-004）、评测 run-1757/1801 两轮 diff≠0（资产/窗口，终态 diff=0）
- 尚未执行：A6 过期 token 注入；publish 端点参数形态定界；D41 真值转写勘误处置（等用户裁决，H5）
- N/A：远程臂（百炼/deepseek/rerank 全 0 调用红线）、L5 跨机

## 遗留与 D44 候选
质量型 bad-case 修复（基线演进须裁决）/ D41 30 unaligned 真值勘误 / hybrid+rerank 组合臂 / 词面弱相关新数据集 / cross-encoder 本地模型 / sync 终态行保留策略 / 跨机 L5 / seeding 通道 activate 步骤 / 迭代窗口外部实例停机决策（18080）
