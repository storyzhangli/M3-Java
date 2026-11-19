# D36 迭代状态

## 需求编号
D36-R01（检索参数对照实验，DB 增强版任务书执行）

## 基线核实
- 双基线：CURRENT_RUNTIME_BASELINE = chunkSize 500 / overlap 100 / topK 5 / threshold 0（代码真实绑定）；G0_CONTROLLED_BASELINE = 200/20/5/0.25（需求指定，非仓库默认）。
- 数据集版本：D36-DATASET-v1（sha256 67bfdc93091d…，10 问）；D36-CORPUS-v1（3 txt，逐文档 SHA-256 见 corpus-manifest.json）；git 基线 7fecbde。
- collection 标识：d36_exp_g0 / d36_exp_g1 / d36_exp_g2（PG 表级隔离；d36_vector_store 与 d32eval 残留未触碰）。

## 实验矩阵
- 指定矩阵 G0–G4（th 0.25/0.35）+ 阈值 0 平行矩阵 B0–B3（设计调整见 design-adjustment-note.md：实测 Top1 最高 0.2499 < 0.25，指定阈值高于哈希分数区间）。
- 切分组独立表重建（FIXED_SIZE）；查询组复用 g0 表；预热 3 轮不纳入 + 正式 3 轮；固定问题顺序。
- chunk 数：g0=5（2/2/1）、g1=3（1/1/1）、g2=5（2/2/1）。

## 指标结果
- 详见 experiment-matrix.csv / retrieval-experiment-summary.json / experiment-conclusion.md。
- 关键：阈值影响最大（Recall 1.0→0.7778→0.4444）；chunkSize 500 伤召回（0.7778→0.6667）；overlap/TopK 本语料无显著效应；耗时 1.7–2.7ms 检索段主导。
- 四态统计：自测 PASS 17/FAIL 0；检索记录 PASS 270/FAIL 0/BLOCKED 0/NOT RUN 0；错误案例 111 条（空检索/漏召回/无答案误检，全部留痕）。

## 结论与偏差
- 影响最大参数：阈值（Recall 与精度双向）；推荐 200/20/5/th≈0.22–0.24（数据校准窗口 0.2144–0.2408）。
- 本地哈希 Embedding 偏差：分数非语义概率，结论仅限冻结模型/语料/问题集，不外推神经 Embedding 与生产规模。

## 代码增量（3 文件，只增不改语义）
- SimilaritySearchService / SimilaritySearchResultView：三段计时字段（queryEmbeddingMs/vectorSearchMs/retrievalTotalMs）。
- DocumentImportService.handleText：txt/md 写入单页页表，打通 indexFromPages（修复真实功能缺口）。

## 真实失败与修复留痕（同根因均一次修复成功，未触发熔断）
1. txt 导入不写页表 → indexFromPages NO_PAGES：handleText 最小增量修复。
2. 跨切分配置 MySQL chunk 关联数不一致 → COUNT_MISMATCH：组间清理本实验 chunk 行（CleanupChunkRows，仅 3 个实验 documentId）。

## 证据与遗留
- 证据：docs/evidence/D36/（00-baseline-scan、design-adjustment-note、corpus/、dataset、manifest、raw jsonl、summary、3 CSV、conclusion、metrics-selftest、README）。
- 脚本：docs/temp_docs/D36/（freeze-dataset.ps1、experiment/*.java、DbProbe/StaleProbe/CleanupOwnDocs/CleanupChunkRows，保留未删）。
- 日志：logs/D36/boot1-g0.log、boot2-g1.log、boot3-g2.log（含 .err.log）。
- 遗留：Top-K/overlap/耗时结论受小语料限制；神经 Embedding 阈值需重标定；MySQL chunk 关联表跨配置复用会触发 COUNT_MISMATCH（生产若需多配置并存应按 collection 维度扩展关联键，本次未改业务键）。
