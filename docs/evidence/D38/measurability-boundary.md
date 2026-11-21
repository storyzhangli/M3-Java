# D38 可测性边界与离线天花板（measurability-boundary）

> 本文件如实划定 D38 one_ 轨在当前环境下的可测边界。凡不可测项标 N/A 并写明原因，
> 不以 0 或伪造值冒充（任务书 H7）。与状态文件 `iteration_records/D38_state_R01.md` 配套。

## 1. 后端三态：无内存兜底

one_ 轨后端只有 `ONE_PGVECTOR / BLOCKED / UNREACHABLE` 三态，**删除了 D37 的 IN_MEMORY 后端**。

- 原因：one_ 使用神经 Embedding 模型（qwen3.7-text-embedding，1024 维），无法用本地哈希词面模型复刻其向量空间；
  若以 local 哈希向量冒充 one_ 检索结果，会污染 vectorSpaceId 契约（H3）并伪造质量指标。
- 判定（oneeval.EnvProbe）：`!appUp || !pgReachable → UNREACHABLE`；`!oneEndpointOk || !appRetrievalOk || !corpusOk → BLOCKED`；
  三者皆真 → ONE_PGVECTOR。无内存降级路径，离线即 BLOCKED，不产出假基线。
- 本次 run1 探测：mode=OFFLINE（AI_MODE 非 deepseek、无 ChatModel），backend=ONE_PGVECTOR（检索链路真实可用）。

## 2. mock 生成态（AI_MODE=mock）

- 当前环境 AI_MODE=mock，容器无 ChatModel Bean → one_ ask 的生成段返回
  `generationStatus=NOT_CONFIGURED / errorCode=MODEL_NOT_CONFIGURED`，**不产出真实答案文本**。
- 可测部分：检索段（真实百炼 Embedding + pgvector）、证据评估决策链
  （生命周期 fail-closed → CONFLICT → NO_EVIDENCE → LOW_RELEVANCE → CLARIFY → INSUFFICIENT_EVIDENCE → ANSWER）、
  引用契约（sourceId=documentId / citationId=S\d+ / snippet≤200 / url=null）。
- 不可测部分（N/A）：真实 LLM 答案质量、答案-引用一致性（Grounding）、幻觉率、SSE 流式首 Chunk 延迟。
  这些需 deepseek 真实 ChatModel，留待后续迭代（D39+）在预算允许时取证。
- 实证：QA-001 → ANSWER + NOT_CONFIGURED（sources=3，引用契约全符合）；无关问题 → REFUSE + SKIPPED_NO_EVIDENCE。

## 3. SSE 流式输出：D38 未实现（N/A）

- D38 one_ ask 为同步 API（POST /api/v1/one/rag/ask），**未实现 SSE 流式端点**。
- 原因：任务书 D38 范围为双轨 Embedding 接入 + 入库/检索/ask 同步闭环；SSE 与同步 API 一致性属后续迭代。
- 影响：queryEmbeddingMs/vectorSearchMs/retrievalTotalMs 三段计时可测（同步检索），
  但流式 TTFT、流式-同步质量一致性不可测（N/A）。local 轨既有 SSE 能力未被 one_ 复用或改动。

## 4. 跨机复现 L5：NOT RUN（单机环境）

- 当前为单机本地环境（Windows 本机 + 局域网 PG 192.168.200.153 + 百炼云端）。
- L5 跨机复现（不同主机/网络/时区下基线一致性）默认 NOT RUN，如实标注，不冒充。

## 5. 删除竞态：mock 下不可观测（N/A）

- 入库/删除并发竞态（删除进行中检索命中已删向量）在 mock 生成态 + 单机低并发下不可稳定复现。
- 沿用 D37 口径标 N/A；one_ 删除链路本身已验证（DELETE 分批 + chunks remaining=0 + reconcile orphan/missing=0）。

## 6. 远端非确定性与基线字节一致（C12/C13 BLOCKED）

- **实测事实**：远端 query Embedding 每次调用存在 ~1e-4 量级浮点抖动。
  同一存储片段 DOC-SERVO-TROUBLE-002-C03 在 QU-005 三轮 distance 漂移 ~5.5e-4（存储向量固定、PG 8 行等效精确检索，漂移只能来自远端查询向量）。
- **分歧点**：QU-005（噪声题）rank2/rank3 两个无关片段 similarity 差仅 2.4e-5，被抖动翻转排序。
  全部 20 问三轮 decision/errorCode/generationStatus 完全一致，语义质量全稳定；分歧纯属近似平局无关命中排序。
- **门禁结论**：严格排序字节一致门禁对任何近似平局对不可长期保证 → C12 BLOCKED（REPEAT_RESULT_DIVERGED），
  C13 基线不冻结（§4.3.5 不冻结假基线）。用户 2026-11-21 裁决接受如实 BLOCKED。
- **canary 边界**：P0 G5 canaryCosine=1.0 为单次观测（该文本恰为确定性观测）；评测器 canary（QA-001）排序一致因其 top1 分离度大，
  排序稳定不证明向量逐位相同。抖动具输入依赖性，近似平局文本才暴露。
- **明细证据**：`runs/divergence-analysis.json`（逐轮 similarity/distance、最小间隙、漂移幅度）。
- **后续可选方向**（未采纳，留待用户未来裁决）：tie-band ε 平局带投影修订 + 补跑，可在语义稳定前提下冻结基线；本轮按任务书默认接受 BLOCKED。

## 7. 模型版本与参数边界

- modelVersion=remote-v1 为**配置级版本指纹标签**（百炼服务端未暴露独立版本号）；服务端模型变更须人工 bump 并重建 one_ 空间。
- maxInputTokens：API 响应/文档未暴露单条 Token 上限 → 以 maxInputLength=8192（UTF-16 字符，非 Token）做输入长度校验，超长拒绝不截断。
- normalized=false（P0 G4 实测 L2 范数 0.0218，未归一化）；COSINE 距离对模长不敏感，口径与 local 轨一致，不阻断。
- dimensions=1024（P0 G2 实测，≤2000 pgvector HNSW vector 红线）；maxBatch=16（P0 G3 实测 25→400）。

## 8. 可测项汇总（已真实取证）

| 维度 | 可测性 | 证据 |
|---|---|---|
| 远程 Embedding 真实调用/维度/批量/归一化/确定性单次 | 可测 | probe/ |
| one_ 入库幂等/计数一致/strategy 切换/reconcile | 可测 | index/ |
| one_ 检索契约/过滤下推/越界拒绝/零向量拒绝/三段计时 | 可测 | retrieval/ |
| 跨空间隔离/兼容性三态 | 可测 | isolation/ |
| ask 决策链/引用契约/CONFLICT/生命周期 fail-closed | 可测（mock 生成态） | ask/、special-cases/ |
| 重试分类/退避/脱敏/usage 累计 | 可测（本地桩受控注入，零远程） | resilience/ |
| local 无回归（@Primary 零行为变更） | 可测 | regression/ |
| 评测语义质量稳定性（20 问决策三轮一致） | 可测 | runs/raw-results.jsonl |
| 评测排序字节一致（近似平局） | **不可测（远端非确定性）** | runs/divergence-analysis.json |
| 真实 LLM 答案质量/Grounding/幻觉率 | N/A（mock 无 ChatModel） | — |
| SSE 流式/TTFT/流式-同步一致性 | N/A（D38 未实现） | — |
| 跨机复现 L5 | NOT RUN（单机） | — |
| 删除竞态 | N/A（mock 不可观测） | — |