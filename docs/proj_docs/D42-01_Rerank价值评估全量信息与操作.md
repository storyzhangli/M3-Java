# D42-01 Rerank 价值评估全量信息与操作

> 迭代：D42 / R01（one 仓 `E:\AI\project\AI-Learning-one\M3-Java`）｜ 主题：RAG 精排（Rerank）可插拔分支与价值评估实验
> 骨架基线：a086fa2 ｜ 结论：**not-adopt**（仅排序收益不足，工程护栏全部达标，代码保留默认关闭）
> 编制：2026-11-25。本文=全量事实落表，所有数值可回溯到 `docs\evidence\D42\` 对应文件。

---

## 1. 一句话结论与裁决表

在冻结 20 题数据集 + PGVECTOR 检索（topK=5, threshold=0.0）上，对三种精排臂实测排序敏感指标：
**ΔMRR@5=+0.025、ΔnDCG@5=+0.015、Δhit@1=+0.05**，低于预注册收益门 **+0.05/+0.05**；
延迟（local p95=1ms / remote p95=204ms）、fail-open 触发率（0/841=0%）、费用（¥0.2781/千问）全部达标。
按 KP4 冻结阈值裁决 **not-adopt**。

| 冻结阈值（decisionThresholdsPrefrozen） | 实测 | 判定 |
|---|---|---|
| ΔMRR@5 ≥ +0.05 | +0.025 | **FAIL** |
| ΔnDCG@5 ≥ +0.05 | +0.015329 | **FAIL** |
| hit@1 不降 | 0.45 → 0.50（+0.05） | PASS |
| local 附加延迟 p95 ≤ 300ms | 1ms（A1/A3） | PASS |
| remote 附加延迟 p95 ≤ 1500ms | 204ms（A2） | PASS |
| fail-open 触发率 ≤ 1% | 0/841 = 0% | PASS |
| 远程费用 ≤ ¥3.0/千问 | ¥0.2781/千问 | PASS |

权威证据：`evidence\D42\rerank\runs\decision\decision-verdict.json`、`rerank\decision.md`、`rerank-protocol.json`。

## 2. 冻结口径（实验前锁定，全程未回改）

| 项 | 值 |
|---|---|
| 数据集 | D37 冻结 20 题 v1.0，SHA256 前 12=`75cc8d83cfb2`（任务书 60 为编制误差→D-EX1） |
| 基线 | A0 baseline.csv SHA=`0c3ad8f6efc9…361b`（A0/A1/A2/A3 各轮全等） |
| 语料 | PG `d42_vector_store` 8 行，ID 面指纹=`2cf619bd…`（≠前代 10fc69d5=模块 NAMESPACE 设计，D-EX2；内容面 c377bb2f 与前代逐字节一致） |
| 检索参数 | topK=5, threshold=0.0, warmup=3, rounds=3 |
| 排序指标 | MRR@5 / nDCG@5（二值相关 rel∈{0,1}）/ hit@1 / 首个相关命中位次均值 |
| 远程预算帽 | calls≤400 / tokens≤2M / 费用≤¥5（单实例） |
| 单价 | gte-rerank-v2 ¥0.8/1M tokens（实测 usage 计费，D4 裁决） |

## 3. 臂定义与执行结果

| 臂 | 定义 | 启动 env | 观测行数 | flag 分布 | 排序指标（60 记录面） | 附加延迟 p50/p95/max |
|---|---|---|---|---|---|---|
| A0 | rerank 缺席（Bean 不装配）＝基线臂兼 C05 disabled | 无 | 0 | — | MRR .475 / nDCG .484671 / hit@1 .45 / FRR 1.1（20 题面） | — |
| A1 | local 词法精排（中文 bigram，确定性，零成本） | RERANK_ENABLED=true | 120 | REORDERED 96 / IDENTITY 24 / FAILED_OPEN 0 | .5 / .5 / .5 / 1.0 | 1/1/2 ms |
| A2 | 远程 gte-rerank-v2（**JDK HttpClient 直连**，SDK 通道 NPE→D-EX8） | +RERANK_REMOTE_ENABLED=true +DASHSCOPE_API_KEY(env) | 120 | 同上 | .5 / .5 / .5 / 1.0 | 128/204/392 ms |
| A3 | A1 + max_output=3 截断臂（改集合，单独标注） | RERANK_MAX_OUTPUT=3 | 120 | REORDERED/IDENTITY + OUTPUT_TRUNCATED 117/120（期望） | .5 / .5 / .5 / 1.0 | 1/1/1 ms |

关键通道事实（**D-EX6**）：D37 冻结评测器 PGVECTOR 后端**直连 PG 相似度检索，不经 app 的 rerank 挂点**——评测通道 baseline.csv 恒为原始向量序（A1/A2/A3 的 baseline SHA 与 A0 全等即此故）。因此**排序指标改由 app ask 日志观测行通道复算**（RagOrchestrator 在 rerank 步输出 `rerank order 观测: orderedChunkIds=…`，enabled 臂才产生，A0 零影响）。

## 4. 操作命令全录（可复现）

```powershell
# P0 探测：盘点（PG 8 行指纹 + MySQL 治理面）、DDL 建表×2 幂等、seeding
java D42VectorProbe <pgUrl> <pgUser> <pgPass> d42_vector_store <myUrl> root <myPass>   # tools\D42VectorProbe.java
java D42DdlSeed docs\temp_docs\D42\ddl\d42_schema.sql <jdbcUrl> root <pass>            # ×2 零报错零变化
.\docs\temp_docs\D42\d42_index-corpus.ps1                                              # 3 文档 DELETE→INDEX→VERIFY

# C01 编译与测试
mvn -B -f D42\pom.xml -pl D42_RERANK test          # 终态 100/100 绿（HTTP 通道改造后复跑=p5-mvn-test-full.txt）

# P3 实验臂（评测通道 + harness 通道双轨）
.\docs\temp_docs\D42\d42_rerank-experiment.ps1 -Arm A0            # runExit=1=冻结基线已知 FAIL 契约，非执行失败
.\docs\temp_docs\D42\d42_instance.ps1 -Action start -Arm A1       # 注入臂 env 启动
.\docs\temp_docs\D42\d42_rank-harness.ps1 -Arm A1                 # 20 题×(warmup3+3) ask，日志提取观测序
.\docs\temp_docs\D42\d42_rank-compare.ps1 -Arm A1                 # 按题去重复算 MRR/nDCG/hit@1/FRR + Δ
.\docs\temp_docs\D42\d42_decision.ps1                             # 读协议冻结阈值 → 机器裁决
.\docs\temp_docs\D42\d42_instance.ps1 -Action stop

# P4 异常矩阵（假端点零外呼）与 C05/清理
.\docs\temp_docs\D42\d42_exceptions.ps1                           # X/Y/Z 三自定义实例 E1/E2/E4/E5
mvn -B -f D42\pom.xml -pl D42_RERANK test -Dtest=DashScopeRerankAdapterOfflineTest#noopOnEmptyCandidates+…(逗号)  # E3 权威=定向单测 3/3
```

**D-EX7 教训（harness 首跑全 EMPTY）**：ask body 传 `strategy='PURE_VECTOR'` 被 SimilaritySearchService 当 **chunk metadata 等值过滤**（夹具 strategy=STRUCTURE）→ 命中被滤空。该字段不是"检索策略"开关；且 `/rag/ask` 字段名=`question`，`/retrieval/search`=`query`。修正=只传 question+topK。

## 5. 异常矩阵 E1–E5（`exceptions\exceptions-matrix.json` allPass=true）

| 案例 | 注入方式 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| E1 缺 Key | Y 实例无 DASHSCOPE_API_KEY + remote | FAILED_OPEN，零外呼 | FAILED_OPEN calls=0 rerankMs=4 状态 SUCCESS | PASS |
| E2 瞬时失败+单次重试 | X 实例假 baseUrl=http://127.0.0.1:9（discard） | RETRIED+FAILED_OPEN | 双 flag，calls=2（1 重试+1 原始，无放大）rerankMs=50 | PASS |
| E4a 请求超帽 | Z 实例 requestTokenLimit 压小 | OVERSIZED 预检零调用 | OVERSIZED calls=0 | PASS |
| E4b 输入截断 | X 实例 perDocTokenLimit=10 | TRUNCATED | TRUNCATED（与 E2 同行） | PASS |
| E5 预算帽 | X maxCalls=1 | BUDGET_STOP 停发 | BUDGET_STOP（2 call 后停） | PASS |
| E3 空候选 | threshold=0 恒有命中→**在线不可达** | NOOP_EMPTY | **三适配器定向单测为权威**：Tests run:3 Failures:0 | PASS（通道降级不降级证据） |

## 6. C05 双态回归与清理复验

- **disabled**：新代码（观测行+HTTP 通道）复跑 A0 → baseline.csv SHA 字节级全等冻结值 `0c3ad8f6…`；raw 逐行 diff 仅 `timing`×60+`recordedAt`×60（协议声明非基线键）；rerankLines=0 → **PASS**（`regression\c05-verdict.json`）。复跑目录名错位=D-EX10（脚本 `-First 1` 字典序取旧目录，人工按 runExit 时序核验真新目录 `…T175728…`）。
- **enabled**：A1 评测通道 baseline 全等（集合零破坏旁证）+ 观测通道 120 行 REORDERED96/IDENTITY24 → **PASS**。
- **清理**：PG 8 行指纹 `2cf619bd…` 复验一致；MySQL 共享表 5 计数与 postseed 逐项一致（10/3/32/24/7）；d42_table_extract=0（FIXTURE_REMAINING=0）；实例全停。
- **D-EX9（sync 3→0）**：binlog 证实 17:26:06 GTID:23017 单事务 `DELETE FROM d42_document_sync WHERE document_id IN(3 项) AND operation=…` 删除的正是 seeding 16:58:51 写入的 3 条 DELETE|SYNCED **终态行**。代码面核查：DocumentSyncMapper 无任何 delete SQL，全仓（含测试 harness/jdbcTemplate）零 d42_document_sync 删除触点，D42 应用不可能删；发起方在 agent 证据面外（general_log=OFF）。影响=零（终态行非保留资产；裁决输入全在文件）。详见 `cleanup\sync-drift-diagnosis.txt` + `binlog-000387-decode.txt`。

## 7. 费用明细（`rerank\cost.csv`）

| 实例 | calls | tokens | 费用 | 帽判定 |
|---|---|---|---|---|
| harness A2 | 121 | 41,959 | ¥0.033584 | 全 PASS，零失败零重试 |
| evaluator A2 | 120 | 41,694 | ¥0.033372 | 同上 |
| **合计** | **241** | **83,653** | **¥0.066956** | 单实例口径各<400（协议偏差留痕：任务书=单实例累计，实测两实例分别<帽，合计 241 亦<400） |

折算 ¥0.2781/千问（83,653/241×1000×0.8/1e6）。单 call 实测 265–348 tokens。

## 8. 用例四态落表（C01–C20）

| 编号 | 状态 | 证据 | 备注 |
|---|---|---|---|
| C01 | PASS | c01-mvn-test-2.txt + p5-mvn-test-full.txt | 100/100（19 类=15 迁移+5 新增，含 HTTP 改造后复跑）+ 前端 6/6 |
| C02 | PASS | probe\rerank-protocol.json + kp-gates-verdict.md | KP1–KP4 四门禁 + javap 实证 + 阈值先冻结 |
| C03 | PASS | probe\kp2-* | 8 片段；ID 面指纹 2cf619bd 冻结复验（D-EX2 口径修正） |
| C04 | PASS | probe\kp3-selftest.txt | 数据集 SHA ✓；baseline 20×17 ✓；SelfTest 75/75 |
| C05 | PASS | regression\c05-verdict.json | 双态（见 §6） |
| C06 | PASS | rerank\runs\A0\ | baseline d22be515…（raw）；排序基线值入 summary.csv |
| C07 | PASS | runs\A1\ + compare | 确定性=3 轮 inconsistent 0 |
| C08 | PASS | runs\A2\ | 授权后受控执行；NOT RUN 条件未触发 |
| C09 | PASS | runs\A3\ | 截断集合变化单独标注；未与纯重排臂混算 |
| C10 | PASS | rerank\summary.csv + compare\*.json | 公式=KP4 |
| C11 | PASS | rerank\latency.csv | warmup 排除，分臂 |
| C12 | PASS | rerank\cost.csv | 实测 token×0.8/1e6 |
| C13 | PASS | rerank\decision.md + decision-verdict.json | 逐阈值对照；无事后调 |
| C14–C18 | PASS | exceptions\exceptions-matrix.json | E3=单测权威（在线不可达留痕）；allPass=true |
| C19 | PASS | cleanup\ + regression\ | FIXTURE_REMAINING=0；D-EX9 见 §6 |
| C20 | PASS | security\hygiene-scan.txt + 本文档族 + git | 真实密钥/Bearer 518 文件零命中 |
| L5 | NOT RUN | — | 跨机复现（单机环境，如实标注） |

## 9. 踩坑与继承教训（D-EX 全录）

1. **D-EX1** 任务书 60 题/60 行/16 列 = 编制误差，实测 20/20/17，以执行时为准。
2. **D-EX2** ID 面指纹跨代不等是 NAMESPACE 隔离设计，KP2 判据修正为"内容面相等+ID 面冻结新值"。
3. **D-EX3** d42_ MySQL 表骨架未建（任务书预存假设不符），P0 补 DDL×2 幂等。
4. **D-EX4** 配置前缀 `app.ai.rag.rerank` 与既有 `app.rag` 并存互不冲突。
5. **D-EX5** QA-001 锚 uuid 换 6d42 代际 d19a82c3（similarity 0.37974774837493896 不变）。
6. **D-EX6** 评测器直连 PG 不经 app rerank → 排序指标走 app 日志观测通道（本迭代最重要架构发现）。
7. **D-EX7** ask body 的 strategy 是 metadata 过滤字段不是检索策略；PURE_VECTOR 误传致全 EMPTY。
8. **D-EX8** dashscope-sdk-java 2.23.6 TextReRank **全部公开构造实测 NPE**（SynchronizeHalfDuplexApi:28 读 ServiceOption.getProtocol()=null，bytecode 显示 setProtocol 已执行仍 NPE）→ 探针 `probe\D42RerankProbe.java` 留证 → 远程分支重写为 JDK HttpClient + Jackson 直连原生 REST（换通道不降级；记账/三重帽/截断/重试逻辑零改动）。改造两坑：yml CRLF 须用 `\r\n` 做 Replace；HttpClient 不能放实例初始化块（早于构造器赋值 → properties==null NPE），移入构造器。
9. **D-EX9** sync 终态行被 DB 外部会话删除（binlog 定案，影响=零）。
10. **D-EX10** 复跑场景 `Get-ChildItem -Filter 'run-*' | Select -First 1` 字典序取旧目录 → 摘要目录名错位，须按 runExit 时间/新目录存在性人工锚定。
11. surefire 多类方法过滤 `-Dtest=A#m1,B#m2` 用**逗号**，`+` 连接只跑 1 个。
12. 本机 msys2 redis-cli 教训不涉及本迭代；PG 驱动必须 42.7.2（旧版 SCRAM "不支援 10 验证类型"）。
13. fail-open 行为不变性强证：A2 首跑（SDK NPE 全 FAILED_OPEN）与 HTTP 修复轮**评测通道 SHA 相同**——rerank 失败/缺席对检索结果零污染，正是 D5 设计语义的意外实证。

## 10. 实现清单（提交面）

- 新包 `com.zl.d42.rag.rerank` 12 类：RerankPort / RerankRequest / RerankResult / RerankFlags / RerankStrategy / RerankProperties（+baseUrl）/ RerankExceptionClassifier（+HTTP/HttpTimeout 分支）/ RerankHttpStatusException（新增）/ LocalLexicalRerankAdapter / DashScopeRerankAdapter（HTTP 通道版）/ RerankBudgetGuard / RerankAccounting（以磁盘实数为准）。
- 挂点：RagOrchestrator（applyRerank/applyRerankOrder/观测行/budget 6 字段/rerankMs）；RagTimingView/RagBudgetView；application.yml `app.ai.rag.rerank` env 占位段 + base-url 行。
- 测试：D41 15 类迁移（d42 token 适配）+ 5 新类 = 100 用例。
- 脚本源码（git add -f）：d42_index-corpus / d42_instance / d42_rerank-experiment / d42_rank-harness / d42_rank-compare / d42_decision / d42_exceptions / d42_c01-migrate-tests 共 8 个 ps1 + D42DdlSeed.java + D42VectorProbe.java + D42RerankProbe.java + d42_schema.sql。
- 不提交：probe\eval-classes\、*.class、d42-cp.txt、logs surefire、target\。

## 11. 官方溯源（对齐任务书 §8，执行时复核）

- 《DashScope SDK — TextReRank API | 百炼排序模型 Java SDK》：https://help.aliyun.com/zh/model-studio/text-rerank-api （TextReRank.call/TextReRankParam/usage 依据；本迭代 javap+实测证 2.23.6 该封装 NPE，遂改直连 REST，见 D-EX8）
- 《gte-rerank-v2 — 阿里云百炼 | gte-rerank-v2 模型信息与价格》：https://docs.bailian.console.aliyun.com/zh/model-studio/gte-rerank-v2 （0.8 元/百万 tokens、30000 token 请求帽、RPM 5040 依据；实测 usage.total_tokens 有值、results[].index/relevance_score；5 候选常返 4 条=top_n 外低分被上游裁）
- 《LangChain 集成百炼模型代码示例 | 重排序模型价格表》：https://help.aliyun.com/zh/model-studio/use-bailian-in-langchain （gte-rerank-v2 0.0008 元/千 token 交叉印证）
- 《Spring AI Reference — ETL Pipeline / Retrieval | Spring AI 检索》：https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html （VectorStore.similaritySearch topK/threshold 依据）
- D-EX9 取证手段：《MySQL Reference Manual — Binary Log | MySQL 二进制日志》：https://dev.mysql.com/doc/refman/9.7/en/binary-log.html （任务书 §8 外补充，本机 MySQL 9.7.0 log_bin=ON/ROW 实测可用）
- 本地权威依据：D37 冻结基线/数据集/评测器（`docs\evidence\D37\`、`docs\temp_docs\D37\evaluator\src\d37eval\`）、D40 C05 回归口径（`d40_c05-regression.ps1`）、D41 seeding 先例（`d41_index-corpus.ps1`）。
