# D34 证据索引（Evidence Index）

> 迭代：D34「DB 增强版：基于证据回答与无证据拒答」
> 维护规则：按 D34 子目录追加，不删除历史证据；本索引只做导航与口径说明，结论以对应原始文件为准。

## 1. 证据文件清单

| 序号 | 文件 | 类型 | 内容与用途 |
|---|---|---|---|
| 00 | `00-env-probe.txt` | 环境探测 | 工程规模、基线 HEAD、PG 可达性、环境变量存在性、Maven/JDK 版本 |
| 01 | `01-design-and-change-list.md` | 设计 | 新增/修改文件清单、评估口径、配置外置、高风险点 |
| 02 | `02-evidence-index.md` | 索引 | 本文件，证据导航 |
| 03 | `refusal-evaluation-dataset.json` | 评测 | 12 用例逐条 actual/passed + 7 指标（真实执行后填写） |

## 2. 关键事实（来自原始证据，非推断）

- D34 模块 `src` 下 234 个 Java 文件；基线 HEAD `6e38404`（D34 项目基础骨架）。
- PG `192.168.200.153:5432` TCP 可达；真实密码变量为 `PG_VECTOR_PASSWORD`（存在），`PGVECTOR_DB_PASSWORD` 不存在。
- Maven 3.9.16 + JDK 21.0.7；测试树初始为空，全部测试从零创建。
- 全量 `mvn -f D34/pom.xml clean test`：43 用例，0 失败，0 错误，0 跳过，BUILD SUCCESS。
- 评测集 12 用例受控确定性执行全部通过；7 指标按公式计算，无分母为 0 的伪造 0%。

## 3. 执行口径与边界（如实声明）

- 本迭代测试为隔离内存 fixture + 真实运行评估/校验服务的确定性逻辑，对应 Level 3/4 验证。
- 不含真实 ChatModel 与真实 pgvector 端到端，**不冒充 Level 5**；Level 5 需 `AI_MODE=deepseek` + `AI_API_KEY`（PG 凭据已具备）。
- 预算口径为 UTF-16 字符（CHARACTER），不伪称 Token；字符 ≠ Token、Page ≠ Chunk、ChatModel ≠ Embedding。

## 4. 关联日志

- 权威测试日志：`logs/D34/mvn-test-D34-R01.txt`（UTF-8，含 43 用例结果）。
- 早期单测日志：`logs/D34/unit-test-01.txt`（31 用例阶段，保留不删）。
