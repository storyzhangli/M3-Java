# D36 证据索引（检索参数对照实验）

| 文件 | 内容 |
|---|---|
| 00-baseline-scan.md | 基线实扫：双基线、隔离机制、数据库现状、语料、计时缺口决策 |
| design-adjustment-note.md | 阈值地板发现与 B 矩阵设计调整（真实冒烟分数支撑） |
| corpus/*.txt | 冻结导入语料（3 文档，由 equipment-fault-corpus.json 确定性导出） |
| corpus-manifest.json | 语料清单：逐文档 SHA-256、字符数、git 7fecbde、模型/维度/度量 |
| retrieval-evaluation-dataset.json | 冻结问题集 D36-DATASET-v1：10 问 + Chunk 级 goldSpans + 相关性规则 |
| retrieval-raw-results.jsonl | 原始检索记录 270 条（9 组×10 问×3 轮，含三段耗时与完整命中） |
| retrieval-experiment-summary.json | 分组汇总指标（从原始记录复算） |
| experiment-matrix.csv | 实验矩阵总表（Excel 可开，UTF-8 BOM） |
| question-results.csv | 逐问指标明细 |
| error-cases.csv | 错误案例 111 条（EMPTY_RETRIEVAL/MISS_RECALL/UNANSWERABLE_FALSE_POSITIVE） |
| experiment-conclusion.md | 结论：参数影响排序、推荐组合、已知偏差、四态统计 |
| metrics-selftest.txt | 指标公式自测输出（17 PASS / 0 FAIL） |
| iteration_records/D36_state_R01.md | 迭代状态记录 |

配套脚本（docs/temp_docs/D36，保留未删）：freeze-dataset.ps1、experiment/{MetricsCalculator,MetricsSelfTest,ExperimentRunner,SummaryTool}.java、DbProbe/StaleProbe/CleanupOwnDocs/CleanupChunkRows.java。
运行日志：logs/D36/boot{1,2,3}-*.log。
技术文档：docs/proj_docs/D36-01-…全量信息与操作.md、D36-02-…技术文档说明与实践指南.md。
