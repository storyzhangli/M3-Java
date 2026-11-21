# D38_state_R01 —— 阿里百炼 Embedding 双轨接入

- 迭代/切片：D38 / R01
- 基线 commit：e05311f（D38 骨架）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17
- one_ 冻结参数：model=qwen3.7-text-embedding dims=1024 maxBatch=16 normalized=false canaryCosine=1.0（P0 单次观测）
- 阻塞项：B1 WorkspaceId=已提供（BAILIAN_EMBEDDING_BASE_URL 注入）；B2 模型名=有效（G1 PASS）
- **裁决记录**：C12 远端非确定性熔断 → 用户 2026-11-21 接受如实 BLOCKED（任务书默认，不冻结基线）；既有遗留（D30 密码+454 surefire）→ 用户授权全面清理。

## 门禁与用例落表
| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| G1–G5 | PASS | probe\probe-result.json、probe\one-embedding-params.json | 9 次/746 tokens；dims1024≤2000；maxBatch16；NOT_NORMALIZED；canary1.0 |
| C01 | PASS | compile-test.txt | mvn test exit0 BUILD SUCCESS；前端 node --test 6/6 |
| C02 | PASS | probe\ | G1–G5 全裁决，冻结参数脱敏 |
| C03 | N/A | probe\probe-log.txt | 模型有效，负向门禁不触发 |
| C04 | PASS | startup\c04-missing-key.txt、c04-missing-dims.txt、c04-disabled-probe.json | 缺密钥/缺dims fail-fast exit1 明确报错；enabled=false 启动成功+one_ absent+结构性零远程 |
| C05 | PASS | ddl\existing-tables-unchanged.txt(UNCHANGED=true)、ddl\pg-catalog.json | one_ 表 vector(1024)+HNSW cosine；既有4表不变 |
| C06 | PASS | wiring\beans.txt、diff-annotations.patch、qualifier-usage.txt | local(@Primary)+one_ 两对Bean；全Qualifier；git diff仅2注解（无actuator，源码推导+运行时共存实证） |
| C07 | PASS | regression\qa001-local-probe.json(pass=true)、local-vs-d37-quality-diff.txt(QUALITY_FIELDS_IDENTICAL) | top1差≤1e-9；D37评测器完整跑diff字节一致；SelfTest75/75 |
| C08 | PASS | index\ | 8/8 COMPLETED；幂等短路零新增调用；strategy切换稳定ID复现；orphan0 missing0 |
| C09 | PASS | retrieval\ | matches/total/vectorSpaceId/filtered/三段计时；越界/空白/超长拒绝；零向量拒绝 |
| C10 | PASS | isolation\ | local探针delta0；metadata空间分离；兼容性三态全中 |
| C11 | PASS | ask\、special-cases\special-fixtures-results.json | QA-001→ANSWER+NOT_CONFIGURED引用契约全符合；CONFLICT/EVIDENCE_CONFLICT；生命周期REFUSE/NO_EVIDENCE rejected1 |
| C12 | BLOCKED | runs\divergence-analysis.json、runs\run-…-ONE_PGVECTOR\ | REPEAT_RESULT_DIVERGED：QU-005近似平局(Δ2.4e-5)被远端~5e-4抖动翻转；20问语义三轮全一致；用户裁决接受BLOCKED |
| C13 | BLOCKED | baseline\（未冻结） | 依赖C12；不冻结假基线（§4.3.5） |
| C14 | PASS | usage\usage-summary.json | 全程514/15331真实累计跨进程汇总；未超预算；per-phase齐全 |
| C15 | PASS | security\secret-scan.txt | 真实云密钥零命中；D30密码脱敏(2→0)；454 surefire移出跟踪 |
| C16 | PASS | resilience\retry-classification.json | 本地桩受控注入14/14，零远程 |
| C17 | PASS | special-cases\C17-frontend.txt、C17-static-scan.txt | 前端6/6；static零改动；后端危险渲染零命中 |
| C18 | PASS | special-cases\fixture-cleanup.txt | FIXTURE_REMAINING0；两轨夹具残留全0；语料3文档8片段完好 |
| C19 | PASS | docs\proj_docs\D38-01/02、iteration_records\D38_state_R01.md | 双文档+状态齐全；Mermaid Typora兼容（graph TD/subgraph ID[标题]/节点双引号） |
| C20 | PASS | git\commit-stat.txt | 提交信息规定；范围核验无surefire/log/classes/密钥；不push |

## 分级结论
L1=PASS（C01） L2=PASS（C02/C04/C05/C06/C07/C08/C09/C10） L3=BLOCKED（C12/C13 远端非确定性，如实标注） L4=PASS（C14/C15/C16/C18） L5=NOT RUN（单机）

## 预算实耗
calls=514/1000 tokens=15331/200000（来源：API usage 真实累计，跨进程手工汇总）
- P0 直连：9/746（PowerShell 直连，不经应用计数器）
- P3 入库进程：5/689（已停）
- 当前进程：500/13896（P4 10/272 + eval run1 484/13424 + 夹具 6/200）
- 剩余 486 次/184669 tokens；C16/C04-disabled/local回归零远程
- run2 因熔断未执行，实耗低于全规格两跑预估（968），514 为真实终值

## 诚实声明
- 已执行并通过：G1–G5、C01/C02/C04/C05/C06/C07/C08/C09/C10/C11/C14/C15/C16/C17/C18/C19/C20；oneeval 编译+SelfTest73/73；run1 完整3轮语义全稳定；既有遗留清理（D30脱敏+surefire移除）。
- 已执行但失败：C12 轮间字节一致门禁（QU-005 近似平局排序翻转，远端非确定性实锤，非评测器缺陷）；run1 不冻结基线。
- 尚未执行（建议后续验证）：run2（用户裁决接受BLOCKED，本轮不跑）；真实ChatModel生成态/SSE/跨机L5（后续迭代）。
- N/A 项及原因：C03（模型有效）；L5跨机（单机）；删除竞态（mock不可观测）；真实答案质量/Grounding/幻觉率（mock无ChatModel）；SSE（D38未实现）。详见 measurability-boundary.md。

## 熔断与裁决处置记录
- 命中 §4.3.5（非确定性致基线无法字节一致）：驱动 FATAL 终止，run2 未执行；分歧明细落 runs\divergence-analysis.json；用户接受如实 BLOCKED。
- 命中 §4.3.2（密钥泄漏扫描命中）：发现既有 D30 文档硬编码真实 PG 密码 + 454 surefire 跟踪；报告后用户授权全面清理；D30 脱敏 before2 after0，surefire git rm --cached（本地保留），.gitignore 加规则；C15 复扫 PASS。

## Git
- 提交信息：D38 项目迭代R01 阿里百炼Embedding双轨接入（one_隔离）
- 范围与核验：git\commit-stat.txt（git diff --cached --stat + 提交后 git status）
- 不 push；不改动 D37 已提交资产（D30 脱敏为用户授权安全修复）