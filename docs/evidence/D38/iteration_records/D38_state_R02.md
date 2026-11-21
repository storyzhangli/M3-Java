# D38 项目迭代状态 — R02 混合检索实验（BM25 + 向量 + RRF）

- 状态文件：`docs/evidence/D38/iteration_records/D38_state_R02.md`
- 迭代日期：2026-11-21
- 基线 HEAD：`0652b25a19b5ec083b87675f56e662b4e572465b`（R01 提交，D37 冻结基线复核通过）
- 本轮提交：见文末 Git 记录（不 push）

## 1. 范围与决策

| 项 | 结论 |
|---|---|
| 实验分支 | `com.zl.d38.hybrid`（local 轨内，`@ConditionalOnProperty` 默认关闭） |
| 最终决策 | **ROLLBACK（保持 hybrid 默认关闭）**，机器规则判定，详见 comparison |
| 查询改写 | 不做（任务书 R02 范围外） |
| one_ 轨 | NOT RUN（R01 已交付，本轮零触碰） |
| 远程调用 | 0 次（全程 AI_MODE=mock，DeepSeek/百炼均未调用） |

## 2. 验收项 C01–C18 状态

| 项 | 内容 | 状态 | 证据 |
|---|---|---|---|
| C01 | 基线复核（HEAD/D37 数据集/语料哈希） | PASS | `docs/evidence/D37/baseline/v1.0/PGVECTOR/`（只读复核） |
| C02 | D37 链路零改动 | PASS | 仅读共享链，diff 见 C05 |
| C03 | 倒排索引指纹跨进程复现 | PASS | `hybrid/c04/c04-on.json`（fingerprint=10fc69d5… 与 P0 冻结一致） |
| C04 | hybrid 开关双态（关=无路由且共享链不漂移；开=指纹复现） | PASS | `hybrid/c04/c04-off.json`、`c04-on.json` |
| C05 | D37 基线回归（d37eval 原样复跑逐字节 diff） | PASS（exit0） | `hybrid/regression/`（REGRESSION_PASS） |
| C06 | 三轨隔离（评测前后 PG 三表行数零变化、零远程） | PASS | `hybrid/c06/c06-pre.json`、`c06-post.json` |
| C07 | RRF 手算正确性（SelfTest 直调应用真实类） | PASS | 84/84，`hybrid/evaluator/selftest.log` |
| C08 | 索引 stats/rebuild 三方一致 | PASS | `hybrid/c08/c08-rebuild.json`（含 PS 误判修正注记） |
| C09 | 混合分数语义（RRF 融合分、similarity=null 不伪造、向量原值保留） | PASS | `hybrid/c09/c09-semantics.json` 七断言 |
| C10 | 记录数口径 = 20×3×2=120/单run；两 run 合计 240 | PASS | runA/runA2/runB2 各 120 行 |
| C11 | 确定性：同命令双跑质量投影逐字节一致 | PASS（exit0） | `hybrid/c11/diff-A2-B2.txt`；跨版本对 `diff-A-A2.txt` 亦一致 |
| C12 | 双策略指标矩阵现算 | 完成 | `hybrid/compare/metrics-matrix.csv` |
| C13 | 逐题案例分类 | 完成 | `hybrid/compare/cases.md`（7 题变化） |
| C14 | QF-004 翻案检查 | 未翻案（REFUSE→REFUSE） | `hybrid/compare/decision.txt` |
| C15 | SelfTest 扩展全过 | PASS 84/84 | 同 C07 |
| C16 | 决策规则机器化（KEEP/ROLLBACK/INCONCLUSIVE） | 完成 | `hybrid/compare/decision.txt` verdict=ROLLBACK |
| C17 | 密钥卫生扫描（R02 新文件零真实密钥） | PASS | 扫描记录见本文 §5 |
| C18 | 双文档 + 状态文件 + Git 提交不 push | 完成 | `proj_docs/D38-03`、`D38-04`、本文件 |

## 3. 主矩阵核心结果（runA2，ABAB 交错 3 轮）

| 指标 | PURE_VECTOR | HYBRID_RRF | Δ |
|---|---|---|---|
| hitRateAtK / recallMacro | 1.0000 / 1.0000 | 1.0000 / 1.0000 | 0 |
| precisionMacro | 0.2222 | 0.2222 | 0 |
| mrrMacro | 1.0000 | 1.0000 | 0 |
| falseRefusalRate | 1.0000 | 0.9000 | −0.10 |
| 四态 PASS/FAIL | 0/20 | 0/20 | 0 |
| retrievalTotalMs mean | 2.16 | 2.98 | 1.38x |

- 命中来源（HYBRID 末轮 96 hits）：BOTH=66、VECTOR_ONLY=29、KEYWORD_ONLY=1。
- 判定依据（冻结规则）：Δprecision=+0.00pp < +1pp 门槛 → ROLLBACK。
- **口径修正**：任务书 P6 引用的"基线 precision=0.4"与 D37 冻结记录不符；`docs/evidence/D37/baseline/v1.0/PGVECTOR/evaluation-metrics.json` 实际 precisionMacro=0.2222、PASS=0/FAIL=20。Δ 公式一律取实际记录值。

## 4. 已执行通过 / 执行失败已修正 / 未执行

**已执行并通过**：C01–C16 全表；三次评测运行（runA 修正前、runA2/runB2 修正版，exit=1 为 mock 基线含 FAIL 的预期语义）；C05 diff exit0；C11 双 diff exit0。

**执行中发现并已修正**（均留注记）：
1. SelfTest 夹具 F5 交错名次手算错（RRF 凸性：A=C=0.0322664584959667 > B=0.03225806451612903）；
2. hybrideval 运行 classpath 缺 slf4j-api 导致 InvertedIndex 静态块 ClassNotFound；
3. C08 判定 PS 误报：`Select -Unique` 单值返回字符串本体被 `[0]` 取首字符，改 `Sort-Object -Unique` 复核；
4. C04 手打问句缺"出现"二字致 sim=0.3544 虚惊漂移，改从数据集程序化提取原文后精确复现 0.379747748374939；
5. summary.md 耗时读路径 bug（从 overall 读，数据在策略根级），修后 runA2/B2 展示正常（metrics json 一直完好，不影响 diff/Compare）；
6. C06 after 首版仅 PG 段（会话未注入 MYSQL_PASSWORD），补齐后 PG 三表行数 before/after 恒等 8/8/8。

**未执行（如实申报）**：
- OPT 消融实验（任务书可选项，未做；主矩阵已产生确定性 ROLLBACK 判定，消融不改变主结论）；
- 查询改写（R02 范围外）；one_ 轨评测（NOT RUN）；真实 LLM 端到端（本轮设计上禁用）。

## 5. C17 卫生扫描记录

- 扫描范围：`D38/hybrid` 源码、`application.yml`、`docs/temp_docs/D38/**`、`docs/evidence/D38/hybrid/**`；模式：sk- 密钥、password 字面量、Bearer 长令牌等。
- 命中 3 处均非 R02 引入的真实密钥：① yml `MYSQL_PASSWORD:123456` 为既有本地默认（按项目规范暂不动，不描述为生产适用）；② R01 假夹具 `FAKE_KEY/faketoken`（故意伪造）；③ 环境变量名读取（假阳性）。
- 提交排除：`*.log`、`hybrid_evaluator/out/`、`hybrid_rowcount/classes/`（编译产物）；密钥全程仅会话环境变量注入，未落文件、未写 PATH。

## 6. 遗留风险与后续建议

1. ROLLBACK 判定受限于 8 chunk 小语料 + mock 决策链（precision 天花板 0.2222），结论不可外推到生产语料；若重启该方向，建议先扩语料至 50+ 片并引入真实 Embedding 复核。
2. QA-005 混合策略下出现 RECALL_LOSS + 错误形态迁移（REFUSE→CLARIFY），说明小 k 值 RRF 在词面强相关 chunk 挤占下可能伤害既有召回，扩样时重点观察。
3. hybrid 代码分支保留但默认关闭，后续若删除需连同 18 源文件与 yml 18 行一并走正式迭代评审。
