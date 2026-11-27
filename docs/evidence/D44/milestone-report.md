# RAG v3 里程碑报告（D44 | D25–D43 演进全景 · 阶段收官）

> 定位：M3-Java RAG 主线 19 代迭代的收官答辩报告。所有数字对应证据文件（docs\evidence\），零推测。
> 编译基线：JDK21 / Spring Boot 3.5.16 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17；D44 模块端口 8080，包 com.zl.d44。

## 1. 演进全景表（D25–D44，19+1 代）

| 代 | 代号 | 能力交付 | 权威证据锚 |
|---|---|---|---|
| D25 | DKDCM_RAC | RAG 架构定义+KnowledgeDocument/Chunk 元数据+20 起步问题集 | commit 4dfa417/538d900 |
| D26 | DIABP | 文档导入管线 | 2a18d78 |
| D27 | PDF_WORD | PDF/Word 解析 | 520c263/7ff7de8 |
| D28 | TCASS | 清洗与切分策略 | 4bb9e2e |
| D29 | EBD | Embedding 选型（本地 384 维基线） | 474f55b |
| D30 | pgvector 环境 | PG 向量库环境+密码 env 统一 | 6317a59/3bf4545 |
| D31 | VSUAI | 向量入库幂等 | 65c01e3 |
| D32 | SSATK | 相似度 Top-K 检索 | 532a063 |
| D33 | CAARMCL | 上下文组装最小闭环 | 8804bbd |
| D34 | ABONEARTAWE | 证据回答/无证据拒答（七态决策双轴） | 7ebbabb/0997c5e |
| D35 | RTARC | 引用溯源 Citation | 1c975db |
| D36 | SPCE | 检索参数对照实验（9 组真实 pgvector） | 3736043 |
| D37 | RAG_ESAB | 评测脚本+20 问数据集 v1.0 冻结+双后端基线 | 3afa58b/8c77070 |
| D38 | QWE_MROQRE | 百炼双轨（one_ 隔离）+混合检索/查询改写 | 0652b25/6c16bbe |
| D39 | KAAMF | 知识权限元数据过滤（tenant/dept/device 代数） | 1ca6634 |
| D40 | 文档更新删除一致性 | 同步状态机（DELETE/UPDATE_CLEANUP 三态+原子领取整改） | 2f2af95 |
| D41 | 表格结构化 | PDF 表格抽取（R03 逐格 195/30/0/0；T1–T5 机理修正） | 27ba89b/459e6a3 |
| D42 | RERANK | Rerank 价值评估（默认关 noop；A0–A3 臂决策） | 504ffee |
| D43 | RAG_ETEPAR | 端到端权限与回归（阻断修复 FIX-001..004；矩阵 335 格 LEAK=0） | ea024a5 |
| **D44** | **PDAM（收官）** | **阶段答辩+里程碑：冻结主链零改动、四能力复验全绿、评测 diff=0、答辩四件套** | 本轮（state 文件锚定） |

## 2. 验收能力矩阵（RAG v3 终态）

| 能力 | 达成度 | 权威数值 | 证据 |
|---|---|---|---|
| 文档入库幂等 | ✅ | seeding 3 文档 8 片段；DDL 幂等×2 | kp2-seed-*/ddl-run3 |
| 向量检索 Top-K | ✅ | C05 20 题 hitRate=1.0 | evaluation\run-2026-11-27T175423 |
| 上下文组装 | ✅ | baseline.csv 21 行全列 diff=0（chunk-id 列恒等） | p2-summary.json |
| 证据回答/无证据拒答 | ✅ | C09 3 hard PASS+零伪造引用（sources=0/无[S]标记） | refusal-summary.json |
| 引用溯源 | ✅ | C06 三层全绿（服务 5/5+在线 PASS+数据 5/5 RECONCILED） | citation-*.json |
| 删除同步闭环 | ✅ | C07 8/8+FIX-004 回归 countMismatch=0 | deletion-checks.json |
| 权限元数据过滤 | ✅ | C08 335 格 MISMATCH=0/LEAK=0+A6 过期 7/7+disabled 25 项零差异 | c08-summary.json |
| 表格结构化 | ✅ | D41 R03 恒等复验 195/30/0/0 | c17-cell-diff-rerun.json |
| Rerank | ✅（默认关） | 源码默认 enabled=false/noop；零差异实证 | C18/p2-summary |
| 混合检索（可选臂） | ✅ opt-in | D38/D43 资产在位；D44 锚注入后可跑 | kp2/矩阵 enabled 窗 |
| 评测回归 | ✅ | 四态 0/20/0/0 与 D43 run-1810 完全同构 | p2-summary.json |
| 可复现演示 | ✅ | demo 9/9 ALL-PASS（S0 预清可重复运行） | demo\demo-transcript.json |
| 单测基线 | ✅ | 110/110（继承）+CitationTruthDefense 5/5=**115 全绿** | compile-test2-agg.txt |
| 主链冻结 | ✅ | 352 main+35 test SHA256；drift=0 | freeze-manifest.json |

## 3. 已知边界（诚实声明，禁冒充）

1. **模型级生成 NOT RUN**：AI_MODE=mock + local-hashing embedding，全程 127.0.0.1 零远程；真实模型臂引用质量/答复合规性未测（O3 授权项）。
2. **mock 态 ask 有据题 decision=REFUSE**（D43-C12 已知形态）：检索/证据链真实，生成决策受 mock 限制；评测四态 0/20/0/0 是 OFFLINE 无 ChatModel 通道的固定形态，非回归信号。
3. **模型级故障注入 NOT RUN**：需 FAULT_INJECTION 专用实例（重启预算纪律）；服务级由 FaultInjecting* 单测类覆盖（含于 110/110）。
4. **FABRICATED_CITATION 码不存在**：任务书预设与代码事实偏差，实际三码=TARGET_DELETED/OWNERSHIP_MISMATCH/EVIDENCE_NOT_EFFECTIVE（以代码为准，lateNotes 已记）。
5. **D43"lifecycle 自愈"叙事不实**（OBS-D44-X1）：零 DEPRECATED→ACTIVE 回写路径，seeding 复活需 DB 显式 UPDATE。

## 4. 成本汇总（本轮 D44）

| 项 | 预算 | 实际 | 备注 |
|---|---|---|---|
| 实例重启 | ≤6 | **3/6** | #1 baseline、#2 enabled+ONE（矩阵）、#3 disabled 评测窗 |
| 用户授权介入 | — | 1 次 | FIX-D44-001 夹具清单机械修正裁决 |
| 业务代码变更 | 0（H1） | **0** | 新增仅测试类×1+证据工具/脚本；freeze-manifest drift=0 |
| Git 提交 | 2（骨架已在+终态） | 1 待 P4 | 冻结锚 498d48f；终态提交不 push |
| 评测长任务 | 1 轮 | 1 轮（60 条 raw） | D37 通道直接复用零迁移 |

## 5. 本轮偏差登记（DEVIATION 汇总）

| 编号 | 内容 | 裁决 |
|---|---|---|
| RE-ANCHOR | 冻结锚 ea024a5→**498d48f**（骨架已被前序提交，408 文件纯新增零改动） | baseline-recheck.txt |
| FIX-D44-001 | 夹具清单 9 行 d43- 残留（与 FIX-D43-001 同根因复发） | 用户授权修正→110/110 |
| OBS-D44-X1 | lifecycle 无自愈回写路径 | OPEN→优化清单 O1 |
| OBS-D44-X2 | 迁移三坑（scene 枚举/chat 追加语义/AddRow 早展开） | 已修-OPEN 观察 |
| OBS-D44-X3 | DocumentController 无 DELETE 端点（405→5000 包装） | OPEN→O4 |
| OBS-D44-X4 | 删除闭环不含登记域（knowledge_document/sync 历史行残留） | OPEN→O10 |
| 断言口径修正 | C09 REFUSE 允许 evidenceCount>0（不可回答型合法）；X7-sync 对照须 ADMIN | refusal/c08 summary |
