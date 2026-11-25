# D42 R01 —— Rerank 采用/不采用决策记录（C13）

裁决时间：2026-11-25T17:51:18+08:00（`d42_decision.ps1` 机器判定，输入全为落盘文件，可复算）。
阈值来源：`docs\evidence\D42\probe\rerank-protocol.json` → `decisionThresholdsPrefrozen`（**先于任何实验结果冻结，全程未事后修改**；`decisionThresholds` 字段为 null 系协议编制留痕，权威以 Prefrozen 键为准）。

## 1. 逐阈值实测对照

| 维度 | 冻结阈值 | A1(local) | A2(remote) | A3(local+截断) | 判定 |
|---|---|---|---|---|---|
| ΔMRR@5 | ≥ +0.05 | +0.025 | +0.025 | +0.025 | **FAIL** |
| ΔnDCG@5 | ≥ +0.05 | +0.015329 | +0.015329 | +0.015329 | **FAIL** |
| hit@1 | 不降（≥0.45） | 0.5（+0.05） | 0.5 | 0.5 | PASS |
| 延迟 | local p95 ≤ 300ms | 1ms | — | 1ms | PASS |
| 延迟 | remote p95 ≤ 1500ms | — | 204ms | — | PASS |
| fail-open 触发率 | ≤ 1% | 0/841 步完成行 = 0%（三臂合并观测面） | 同左 | 同左 | PASS |
| 费用/千问 | ≤ ¥3.0 | ¥0 | **¥0.2781** | ¥0 | PASS |

基线（A0，20 题去重面）：MRR@5=0.475，nDCG@5=0.484671，hit@1=0.45，meanFirstRelevantRank=1.1。
目标臂（A1=A2=A3）：0.5 / 0.5 / 0.5 / 1.0。

## 2. 裁决

**verdict = not-adopt**（相关性收益门未过：ΔMRR@5 与 ΔnDCG@5 同时低于 +0.05 冻结线，三臂一致）。
延迟、费用、fail-open 韧性全部达标——**不采用的唯一原因是排序收益不足**，不是工程缺陷。

理由链（诚实标注证据边界）：

1. **收益天花板由基线决定**：A0 基线 meanFirstRelevantRank=1.1（首相关几乎已在第 1 位），20 题中仅 9 题首位不相关。rerank 即使完美也只能把每题从 1.1 提到 1.0——ΔMRR 理论上限远小于 +0.05 阈值线（本数据集上任何精排器都过不了这道门）。
2. **三臂增益完全相同**（A1 本地词法 = A2 远程大模型 = A3 截断）：说明增益来自"把词面强相关的 chunk 提前"这一平凡信号，cross-encoder 的语义精排在此小规模强词面语料上无额外区分度。
3. **A2 远程臂的排序结果与 A1 逐题一致**（harness 面 3 轮一致 inconsistent=0，compare 面 Δ 相同）：为"高成本精排在当前语料无增益"提供了交叉证据。

## 3. 决策含义与后续路径

- rerank 代码**保留**（默认 disabled、opt-in、零差异已证 C05），不删除——工程上已具备可插拔精排能力与三重预算帽护栏。
- 若未来语料变为**词面弱相关**（如跨文档推理、口语化提问、表格数值查询），rerank 的价值假设需在新数据集上重测；当前 20 题数据集对此**不具判别力**（见 D43 候选）。
- 阈值不因结果回调（熔断条件 5 未触发）；本协议留痕即为"该数据集上 rerank 不值 ¥0.28/千问"的定案。

## 4. 输入文件（全部可复算）

- `runs\compare\rank-compare-A0-{A1,A2,A3}.json`（Δ 计算）
- `runs\A{1,2,3}\rank-metrics-*.json`（60 记录面 + 延迟分布）
- `runs\A{0..3}\arm-summary.json`（记账/flag 分布/SHA）
- `runs\decision\decision-verdict.json`（机器裁决原输出）
- `rerank-protocol.json` decisionThresholdsPrefrozen（冻结阈值）
