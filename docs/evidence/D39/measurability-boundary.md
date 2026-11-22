# D39-R01 可测性边界（任务书 §3.6）

## 可测并已实测（mock 生成 + 真实 PG/MySQL，零远程调用）

| 面 | 手段 | 证据 |
|---|---|---|
| 权限谓词 SQL/JSON Path 下推（Top-K 之前） | KP4 决定性夹具（字面同问句）+ topK=20/th=0 零命中 + 正证对照 | enforcement/pushdown-vector.json |
| 关键词路先过滤后排名同口径 | 精确码 D2P-771 userA 零命中/userB 必现 | enforcement/pushdown-keyword.json |
| RRF 融合闭包 | 集成集合断言 + RrfFusionClosureTest 纯函数单测 | enforcement/fusion-closure.json |
| 身份 fail-closed（无/无效/过期/跨 owner） | 4030 码面 + 会话 4001 时序 | security/t8.jsonl、t4.jsonl |
| 存储故障 fail-closed | env 坏端口双演练（零数据/容器改动） | security/t8-failclosed.jsonl |
| 暴露面收口（preview/raw/rebuild/embeddings） | USER 4030/ADMIN 200/遗留 fail-closed 9 断言 | enforcement/surface-audit-online.jsonl |
| 默认关闭零差异 | D37 评测器 60 记录逐字节 diff + QA-001 delta=0 + 匿名行为探针 | regression/regression-result.json |
| 夹具清理三处核验 | FIXTURE_REMAINING=0（PG/MySQL×3 表）+ 指纹恢复 10fc69d5 | cleanup/cleanup-verify-final.txt |
| 谓词代数/哨兵/缺失键 | 29 Java 单测（AST 结构断言基于实测访问器） | unittest/c01-java-tests.md |

## NOT RUN（原因与留待）

| 项 | 状态 | 原因 | 留待 |
|---|---|---|---|
| one_ 轨真实权限端到端 | NOT RUN | 默认 enabled=false、dims=0、零远程预算；谓词代码已同构注入+单测覆盖 | 后续真实轨切片 |
| 真实生成质量（DeepSeek/百炼） | NOT RUN | ai.mode=mock；本轮验证面在检索层（H8 本就不依赖生成层判定） | 生成态迭代 |
| SSE /chat/stream 全路径权限 | 部分（单测） | AuthContextHolder 快照传递单测覆盖；流式端到端需事件流断言工具 | D40 候选 |
| 跨机 L5 复现 | NOT RUN | 单机环境 | 如实标注 |
| Spring Security/JWT/OAuth2 | N/A | R01 范围外（D1 裁决：本地学习最小可信凭据即够） | 升级切片 |
| 运行中 PG 掉线瞬间降级观测 | 以启动 fail-fast+静态无降级分支替代 | 无破坏性断链手段（禁 Docker 网络操作） | 如需可加 testcontainers 网络故障注入 |

## 判定纪律

- 空检索≠检索失败（fail-closed 命中集合断言基于 code+ids 双维度）；分母 0 指标 N/A。
- 越权判定只认 chunkId/documentId 集合（H8），「模型未输出」永不算通过。
- hybrid 指纹门禁绕不过：夹具态/清理态各以 HYBRID_EXPECTED_FINGERPRINT/冻结值实测复验。
