# D42_state_R01 —— Rerank 价值评估（one 仓）

- 迭代/切片：D42 / R01；骨架基线 commit：a086fa2（执行时 HEAD 复核一致）；本切片交付 commit：<主提交后补记>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / dashscope-sdk 2.23.6（远程通道实测 NPE 后弃用其 TextReRank 封装，改 JDK HttpClient 直连 REST，D-EX8） / MyBatis-Plus 3.5.17 / PG 18.7+pgvector 0.8.7 / MySQL 8（本机 9.7.0 客户端）
- 冻结参数：datasetHash=75cc8d83cfb2…（20 题 v1.0） baselineFingerprint=10fc69d5…（前代锚；d42 ID 面新锚=2cf619bd51401282…，ROWS=8，D-EX2） A0raw=d22be515… A1raw=6e9c6262… A2raw=2c7303fb… A3raw=91788a24… baselineCsvSha=0c3ad8f6efc957e7…361b（A0/A1/A2/A3 全轮相等） denyCode=4030 retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3} rankMetrics={MRR@5,nDCG@5,hit@1,firstRelevantRank} decisionThresholds=<rerank-protocol.json decisionThresholdsPrefrozen：ΔMRR@5≥+0.05 ∧ ΔnDCG@5≥+0.05 ∧ hit@1 不降 ∧ local p95≤300ms ∧ remote p95≤1500ms ∧ fail-open≤1% ∧ 费用≤¥3/千问> remoteBudget={maxCalls:400,maxTokens:2M,maxCny:5} rerankPrice=0.8元/M tokens（gte-rerank-v2）
- 偏差裁决（任务书 D1–D10）：D1 端口适配器挂 orchestrator ✓ / D2 集合不变性+排序敏感指标+C05 双态 ✓ / D3 决策门禁预注册（协议先冻结后实验，未回改）✓ / D4 实测 usage.totalTokens×官方单价 ✓ / D5 fail-open+异常分类（HTTP 非 200 类型化异常）✓ / D6 实验记录走文件面 ✓ / D7 耗时口径=nanoTime+日志 rerankMs ✓ / D8 同冻结数据集 ✓ / D9 骨架已提交 a086fa2+D41 15 测试迁移 ✓ / D10 类型化契约（RerankPort/RerankRequest/Result/Flags）✓
- 执行偏差（D-EX，全部实测留痕）：D-EX1 任务书 60/60/16=编制误差，实测 20/20/17 / D-EX2 d42 ID 面指纹≠前代（NAMESPACE 模块隔离设计；内容面 c377bb2f 与前代逐字节一致补足等价证明） / D-EX3 d42_ MySQL 表未预建，P0 执行 DDL 五表×2 幂等 / D-EX4 rerank 配置前缀=app.ai.rag.rerank（既有 app.rag 并存不冲突） / D-EX5 QA-001 锚 uuid 换 6d42 代际 d19a82c3-…-f2b771b16913（similarity 锚 0.37974774837493896 不变） / D-EX6 评测器 PGVECTOR 后端直连 PG 不经 app rerank → 排序指标走 app ask 日志观测行通道（本迭代核心架构发现） / D-EX7 ask body strategy 字段=chunk metadata 等值过滤非检索策略，误传 PURE_VECTOR 致 harness 首跑 EMPTY（EMPTY 轮留证） / D-EX8 dashscope-sdk 2.23.6 TextReRank 全构造 NPE（probe 留证）→ JDK HttpClient 直连原生 REST / D-EX9 d42_document_sync 3→0：binlog 证实 17:26:06 GTID:23017 外部会话 DELETE 删 seeding 三条 DELETE|SYNCED 终态行；代码面零删除路径；发起方不可归因；影响=零 / D-EX10 C05 复跑摘要脚本 runDir 字典序 -First 1 取旧目录错位（人工按 runExit 时序锚定真目录 …T175728…）

## 门禁与用例落表（四态）

| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| KP1 编译 | PASS | evidence\D42\compile-full.txt | javap 实证 SDK 签名后定通道 |
| KP2 语料指纹 | PASS | probe\kp2-*（7 件） | 8 片段；ID 面 2cf619bd 冻结复验；内容面与前代一致 |
| KP3 数据集门禁 | PASS | probe\kp3-selftest.txt | SHA ✓；baseline 20×17 ✓；SelfTest 75/75 |
| KP4 阈值冻结 | PASS | probe\rerank-protocol.json + kp-gates-verdict.md | decisionThresholdsPrefrozen 先于实验；字段名陷阱留痕 |
| C01 编译+测试 | PASS | c01-mvn-test-2.txt + p5-mvn-test-full.txt | 100/100（19 类=15 迁移+5 新）；HTTP 通道改造后复跑 BUILD SUCCESS 18:20 |
| C02 门禁全过 | PASS | probe\kp-gates-verdict.md | KP1–4 |
| C03 语料绑定 | PASS | probe\kp2-* | 3 文档 DELETE→INDEX→VERIFY 8 行 |
| C04 数据集完好 | PASS | probe\kp3-selftest.txt | 零改动 D37 资产 |
| C05 双态回归 | PASS | regression\c05-verdict.json | disabled：baseline 字节级全等+仅 timing×60/recordedAt×60 diff+rerankLines=0；enabled：集合列 diff=0+观测行 REORDERED×96/IDENTITY×24/FAILED_OPEN×0 |
| C06 基线臂 A0 | PASS | rerank\runs\A0\ | runExit=1=冻结基线已知 FAIL 契约（非执行失败）；raw d22be515… |
| C07 确定性 A1 | PASS | runs\A1\ + compare\rank-compare-A0-A1.json | 3 轮 inconsistent=0；120 观测行 |
| C08 远程臂 A2 | PASS | runs\A2\ | 显式授权 env 注入；121 call 记账；HTTP 通道（D-EX8） |
| C09 截断臂 A3 | PASS | runs\A3\ | max_output=3 改集合单独标注；OUTPUT_TRUNCATED=期望非缺陷 |
| C10 指标矩阵 | PASS | rerank\summary.csv + compare\*.json | 公式=KP4；双口径（20 题去重/60 记录）分列 |
| C11 延迟表 | PASS | rerank\latency.csv | A1/A3 p95=1ms；A2 p95=204ms；e2e 分臂 |
| C12 费用表 | PASS | rerank\cost.csv | 实测 token×0.8/1e6；三重帽全 PASS |
| C13 决策记录 | PASS | rerank\decision.md + decision-verdict.json | verdict=not-adopt；无事后调阈值 |
| C14–C18 异常 E1–E5 | PASS | exceptions\exceptions-matrix.json（allPass=true）+ EX-X/Y/Z log + e3-targeted-tests.log | E3 在线不可达→定向单测 3/3 为权威；E2/E4 假端点零外呼 |
| C19 清理复验 | PASS | cleanup\（verdict 修正版+diagnosis 终版+binlog 解码） | PG 8 行指纹复验一致；FIXTURE_REMAINING=0；D-EX9 归因闭环 |
| C20 卫生 | PASS | security\hygiene-scan.txt | 518 文件真实密钥/Bearer 零命中；仅假 Key sk-INVALID-LOCAL-ONLY×3（E1 注入常量） |
| L5 跨机复现 | NOT RUN | — | 单机环境，如实标注 |

## 分级结论

- L1 编译/单测（100/100）=PASS；L2 门禁与契约（KP1–4/C02/C05）=PASS；L3 实验执行（A0–A3 四臂+双通道证据）=PASS；L4 裁决与异常（C13/E1–E5 allPass）=PASS；**L5=NOT RUN（单机）**。

## 实验与决策结论

- 臂执行：A0/A1/A2/A3 全部真实执行（远程臂 A2 已获显式授权，未触发 NOT RUN 条件）。
- 排序指标（20 题面，vs A0=0.475/0.484671/0.45/1.1）：三臂均 0.5/0.5/0.5/1.0 → ΔMRR@5=+0.025、ΔnDCG@5=+0.015329、Δhit@1=+0.05。
- **决策=not-adopt**：收益门（+0.05/+0.05）未过而延迟（1/204ms）、韧性（fail-open 0/841）、费用（¥0.2781/千问）全达标——不采用仅因排序收益不足。根因：基线 meanFirstRelevantRank=1.1 收益天花板≈0.1 位次；三臂增益完全相同=平凡词面信号，cross-encoder 无额外区分度。代码保留（默认关，opt-in，零差异已证）。

## 成本实耗

DeepSeek calls=0 tokens=0（mock 通道）；百炼 one_ 轨 calls=0（默认关）；远程 rerank 实测：harness 121 call/41,959 tok/¥0.033584 + evaluator 120 call/41,694 tok/¥0.033372，合计 241 call/83,653 tok/**¥0.066956**（单实例均<400 帽，零失败零重试）；夹具清理 FIXTURE_REMAINING=0；PG 指纹 2cf619bd 复验恢复基线。

## 诚实声明

- **已执行并通过**：C01–C13、C14–C18、C19、C20、KP1–KP4 全表（含 A2 真实远程调用 241 次、异常矩阵受控注入、binlog 行级取证）。
- **已执行但失败（留证不删除）**：A2 SDK 通道首跑全 FAILED_OPEN（D-EX8，恰证 fail-open 语义，评测 SHA 与修复轮全等）；harness 首跑 EMPTY（D-EX7 误传 strategy）；A0 评测 runExit=1（冻结基线已知 FAIL 契约）；C01 首轮 4 红（identity 分值/flags 可变/extractUnit 正则不同源，修复后 100/100）。
- **尚未执行**：L5 跨机复现（单机环境）；D41-01/02 双文档（非本轮范围，遗留观察项）。
- **不可归因项**：D-EX9 DELETE 发起方在 agent 可见证据面之外（general_log=OFF、history 已轮转、本/平行会话零触达），结论限定为"外部本机客户端执行，非 D42 任何代码路径"。

## 遗留与 D43 候选

1. hybrid+rerank 组合臂（混合检索有排序增益时价值假设需重测）；2. 词面弱相关新数据集（跨文档推理题）重跑 harness 验证天花板归零假设；3. cross-encoder 本地模型替代远程计费；4. 跨机 L5；5. D41 文档与状态文件缺口（观察项）；6. d42_document_sync 终态行保留策略与共享库外部会话干扰防护（如需断言可加 COUNT 守卫）。
