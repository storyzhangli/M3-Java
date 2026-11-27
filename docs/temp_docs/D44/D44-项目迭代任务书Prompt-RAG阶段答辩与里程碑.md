# D44 项目迭代任务书 Prompt（R01）：RAG 阶段答辩与里程碑（one 仓）

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 证据生文档。沿用 D34–D43 体裁。
> 核心原则：**本轮是 RAG 阶段（D25–D43）收官答辩迭代——冻结主链业务代码零改动，以冻结清单 SHA  manifest 为权威；重跑固定集与四关键能力回归（引用真实/删除同步/权限过滤/无证据拒答）；交付文档答辩四件套（架构图/API 说明/数据说明/可复现演示）+ RAG v3 里程碑报告 + 失败案例文档 + 后续优化清单。mock 态可测性边界诚实声明，绝不把结构断言冒充真实模型引用验证。**
> 编制日期：2026-11-27。编制依据：对 `E:\AI\project\AI-Learning-one\M3-Java\D44` 骨架（HEAD `ea024a5`=D43 R01 **已执行交付**，D44 骨架**未提交**）与 D43 状态文件（110/110、FIX-001..004 全 VERIFIED、双锚 50c457b6/c377bb2f、越权 335 格 LEAK=0）的真实扫描（§1），非记忆假设。
> 硬约束：以本地实际代码为唯一依据；**业务代码零改动**（冻结红线）；不伪造指标/验证/演示结果；空检索≠检索失败；核心契约禁用无约束 Map；保留熔断与 Token 阈值模板；质量列基线漂移=熔断；评测纪律继承 D43（disabled 实例+seeding 错峰+tombstone 窗口）。

---

## §0 迭代总览与硬契约

### 0.1 目标

在 D44 模块（`D44\D44_RAG_PDAM`，包 `com.zl.d44`，端口 **8080**）完成 RAG 阶段答辩与里程碑：①**冻结 RAG 主链**（D43 终态代码基线，冻结清单+终态零漂移复验），更新架构图、API/数据说明与文档库演示，留下后续优化清单；②**重跑固定评估集（20 题）与关键回归**，检查引用真实、删除同步、权限过滤、无证据拒答四关键能力；③交付 **RAG v3 里程碑报告、评测报告、架构图、失败案例文档**。

### 0.2 硬契约（违反任意一条即任务失败）

| 编号 | 契约 | 说明 |
|---|---|---|
| H1 | 主链冻结零改动 | **src/main 业务代码与既有测试语义零改动**；冻结清单 freeze-manifest.json（352 main Java 逐文件 SHA256+既有测试清单）P0 生成、终态复验 **drift=0**；本轮只新增文档/脚本/证据/演示与必要的集成观测单测（新增不改既有）。发现阻断缺陷 → **登记+停止请示**（冻结期修复须用户裁决），不得静默改码。 |
| H2 | 基线与资产只读 | D37 数据集（SHA `75cc8d83cfb2…`，20 题）/基线 20×17/评测器 9 源、D41 真值（225 格，30 unaligned 勘误待裁决）、D39–D43 全部证据只读；C05 质量列 **diff=0**（timing/recordedAt 豁免）；质量型 bad-case 仍只记录不修；基线演进须用户裁决。 |
| H3 | 既有开关默认值不动 | access/hybrid/one_/rerank（not-adopt 保留态）/table/fault-injection/sync-retry 默认值一律不动；矩阵经环境变量注入切换；hybrid 启用须 env 注入 **D44 ID 面新锚**（yml 默认 10fc69d5 为前代锚，不改 yml）。 |
| H4 | 四能力验证真实 + 边界诚实 | 引用真实/删除同步/权限过滤/无证据拒答逐项独立证据链；**mock 态 sources 恒空（D43 C12 可测性边界）→ 引用真实走三层结构断言+边界声明**（§3.2），真实模型臂 NOT RUN 须授权；不得以结构断言冒充真实模型引用验证；分母 0 标 N/A。 |
| H5 | 评测纪律继承 | 评测必须 **disabled 干净实例**且与 seeding **错峰**（DELETE tombstone 瞬态窗口，D43 C05 三段定界教训：39→20→0）；runDir 按 runExit 时序锚定（D-EX10）；**长 eval 脚本禁 Select -First 截管道**；重启预算 KP4 预分配（D43 8/8 耗尽教训），耗尽即停。 |
| H6 | 夹具可清理 + 遗留零触碰 | 夹具 **`d44-` 前缀**（骨架已带 9 件+SHA+truth）；PG **d44_vector_store**（initialize=true 新表）；MySQL d44_ 表 P0 幂等 DDL（D-EX3 先例）+共享表前缀限定清理；遗留 DOC-* 冻结文档集+chunk_other 32 行+d32eval 24 行零触碰；FIXTURE_REMAINING=0 多处核验；**外部并行实例（18080 jdwp）噪声源**：KP2 检测，停机须用户裁决（OBS-D43-X1），窗口内共享库写操作以 COUNT 守卫+差值口径防噪。 |
| H7 | 权限回归全覆盖 | 越权矩阵复跑（D43 335 格口径：主矩阵 285+one_ 臂 50）**LEAK=0**；FIX-002/003 guard 回归（PromptTemplate 4 写面+OneVectorIndex 3 写面）；**补 D43 缺口 A6 过期 token 注入**；拒绝码 4030、审计差值口径（T0 快照→delta）；disabled 态零差异。 |
| H8 | 凭据与预算卫生 | 密钥仅环境变量（PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY）；**JDBC 必用 postgresql-42.7.11.jar**（42.1.4 不支持 SCRAM 报 Unsupported auth type 10，禁 postgresql-4* 通配）；夹具 token 明文仅 temp_docs（不提交）；sk-/Bearer 实值扫描零命中（假 Key 常量标注豁免）；surefire-reports/target/out/logs *.log 不入库；temp_docs 不自动删除；不 push。 |
| H9 | 答辩材料可复算 | 架构图/API/数据说明的每个节点/端点/表列**必须来自实扫**（冻结清单/控制器源码/DDL），不得凭记忆绘制；演示脚本可复现（同命令同输出结构）；里程碑报告的每项能力声明附证据索引（Dxx evidence 路径）；失败案例文档逐条附根因与证据。 |

### 0.3 范围边界（本轮 R01 明确不做）

- 任何业务功能新增/修改/修复（冻结红线 H1）；发现阻断 → 登记请示。
- 质量型 bad-case 修复、D41 真值转写勘误、基线演进：须用户裁决，列优化清单。
- 真实模型臂（DeepSeek/百炼/远程 rerank）：NOT RUN（零远程红线）；引用真实的模型级验证列优化清单。
- hybrid+rerank 组合臂、词面弱相关新数据集、cross-encoder 本地模型（D43 遗留）：列优化清单。
- 外部实例（18080）停机：用户裁决项，本轮只检测+防噪。
- 跨机 L5、SSE 全路径真实模型：NOT RUN。

### 0.4 与用户原始需求的十处已裁决偏差/细化（执行前必读，均有实扫依据）

| # | 用户原文 | 实测事实 | 裁决 |
|---|---|---|---|
| D1 | 「冻结 RAG 主链」 | HEAD ea024a5=D43 R01 终态（157 files +17276/−18）；**D44 骨架未提交**（AM pom ×2、?? src/）；D44 src/main **352 Java 与 D43 同构**（d44 包名/表名/夹具前缀已迁移）；测试 **35 文件**（22 Java=21 测试类+D44TableFixtureGenerator，含 FIX-002/003 新增 PromptTemplateControllerGuardTest/OneVectorIndexControllerGuardTest） | 冻结=**P0 先提交骨架（冻结锚 commit）+ freeze-manifest.json（352 文件 SHA256）+ 终态 drift=0 复验**；业务代码零改动；新增仅限文档/脚本/证据/观测单测 |
| D2 | 「检查引用真实」 | **D43 C12 边界声明：mock 态 sources 恒空=可测性边界**；citations=0 不误放行已证；CitationVerificationService/CitationDeletedClassificationTest 既有 | **三层结构断言**（§3.2）：①服务级受控引用核验（真实 chunkId→PASS/伪造→FABRICATED/已删→CITATION_TARGET_DELETED）②在线 mock citations=0 不误放行③DB chunkId 存在性对账；**边界声明：模型级引用真实 NOT RUN**（须授权真实臂），列优化清单——诚实降级不冒充 |
| D3 | 「检查删除同步」 | D40 链路+FIX-004（indexChunks 本版本重建前置 PG 清理 deleteVectorsForVersion）已 VERIFIED；d44_document_sync 6 原因码+原子领取；D-EX9 外部会话干扰先例 | 删除同步复验=删除闭环（标记→清向量→清关联→双端确认）+旧版清理+TABLE_ORPHAN 对账+重复事件幂等+**FIX-004 回归**（同版本就地覆盖重建不再 COUNT_MISMATCH）；sync 计数 COUNT 守卫+差值口径 |
| D4 | 「检查权限过滤」 | D43 越权 **335 格（285+50）LEAK=0**；FIX-002（PromptTemplate 4 写面）/FIX-003（OneVectorIndex 3 写面）guard 已注入；**A6 过期 token 未注入（D43 缺口）**；审计差值口径 T0=347 先例 | 矩阵复跑 LEAK=0+guard 单测回归+**补 A6 过期 token 夹具**；检索谓词过滤（D39 代数）在线探针；审计 DENIED/ALLOWED 差值取证 |
| D5 | 「检查无证据拒答」 | D34 七态决策双轴：检索失败/无证据/澄清/冲突/拒答均**零模型调用**；RagOrchestrator FAILED/EMPTY 分支；评测器 REFUSAL_ERROR 标签族 | 无证据拒答复验=受控问题集（无证据题/检索失败注入/澄清题）→ 决策码+零模型调用断言（mock 计数）+与 D37 冻结 labels 分布一致（17/10/2 题三族） |
| D6 | 「更新架构图、API/数据说明」 | 20 控制器/19 顶层包/MySQL 共享表+d44_ 表/PG d44_vector_store+one_d44_vector_store；既有 D43-02 有链路描述但无全景架构图 | 架构图=**Mermaid graph TD 全景**（六域主链+双存储+权限代数+同步状态机+实验分支）+六域分解；API 说明=20 控制器端点清单（方法/路径/权限分级/契约）；数据说明=表字典（列/键/关联/生命周期）；**全部从实扫生成**（H9），Typora 0.11.18 兼容 |
| D7 | 「文档库演示」 | 无既有可复现演示脚本；D43 lifecycle-run5 49/49 为断言矩阵非演示 | 演示=**可复现脚本 d44_demo.ps1**（mock 零远程）：登记→导入（PDF 故障码表）→索引→问答（有证据）→无证据拒答→表格抽取→删除→对账归零，逐步输出落 evidence\demo\；演示只读主链不改码 |
| D8 | 「留下后续优化清单」 | D43 遗留 9 项：质量型修复（基线演进须裁决）/D41 30 unaligned 勘误/hybrid+rerank 组合臂/词面弱相关新数据集/cross-encoder 本地模型/sync 终态行保留策略/跨机 L5/seeding 通道 activate 步骤/外部实例停机决策（18080） | 优化清单=**9 项继承+D44 新观察**，逐项标优先级/授权需求/预期影响/证据来源；须裁决项显式标注（质量修复/勘误/真实臂/停机） |
| D9 | 「RAG v3 里程碑、评测报告、失败案例文档」 | D25–D43 共 19 迭代能力已累积（代号见 §1.1）；D43 bad-case **12 条**（质量型 3 族 17/10/2 题、阻断已修 4、流程/资产/环境 5，UNATTRIBUTED=0）；FIX-001..004 根因卡齐全；D42 D-EX1–10、D41 R03 机理修正记录在案 | 里程碑报告=**D25–D43 演进全景表**（迭代/能力/commit/证据索引）+验收能力矩阵+已知边界+成本汇总；评测报告=本轮重跑 20/60 双口径+与 D43 基线对照；失败案例文档=**12 bad-case+4 FIX 根因卡+D-EX 偏差+R03 机理修正**汇总（六域分类+证据索引） |
| D10 | 「重跑固定集与关键回归」 | D43 C05 三段定界（run-1757=39 资产污染→run-1801=20 tombstone 窗口→run-1810=0）；重启预算 8/8 耗尽；双锚 idAnchor=50c457b6…/contentAnchor=c377bb2f62d7b71b…（V2 口径） | 重跑=**D43 纪律全套继承**（H5）；D44 ID 面新锚 KP2 实测+内容面 c377bb2f 恒等；重启预算 KP4 预分配（建议 ≤6 次）；C05 diff=0+D41 R03 195/30/0/0 复验+rerank 默认关零差异 |

---

## §1 基线核实结果（编制时真实扫描，执行时须复核）

### 1.1 已核实事实（证据均为实读/实测）

| 项 | 实测值 |
|---|---|
| Git 基线 | HEAD `ea024a5`（D43 R01 单提交，157 files +17276/−18，不 push）；**D44 骨架未提交**（AM D44/pom.xml、AM D44/D44_RAG_PDAM/pom.xml、?? src/）；前序 `c47be85`（D43 骨架）、`27ba89b`（D41 R03）、`829d7c5`（D41 R02）、`504ffee`（D42 R01） |
| 模块 | `D44\D44_RAG_PDAM`，包 `com.zl.d44`，端口 **8080**；main Java **352 个**；**20 控制器**；19 顶层包 access/adapter/common/config/controller/conversation/device/document/dto/history/hybrid/knowledge/logging/observability/one/prompt/rag/resilience/service |
| 技术基线 | Spring Boot 3.5.16 / JDK21 / Spring AI BOM 1.1.8 / MyBatis-Plus 3.5.17 / Spring Cloud 2025.0.0 / SCA 2025.0.0.0 / dashscope-sdk 2.23.6 / mysql-connector 8.0.33 / PDFBox 3.0.7 / PG 18.7+pgvector 0.8.7 |
| D43 权威终态 | 终测 **110/110**（继承 99→100+FIX 新增）；**FIX-D43-001..004 全 FIXED-VERIFIED**：001=FIXTURE-SHA256.txt manifest 名称错位（d42 前缀未重生成，纯测试资产）；002=PromptTemplateController 4 写面无 ADMIN 守卫（D15 遗留）→guardAdmin+审计 prompts.*；003=OneVectorIndexController 3 写面无守卫（D38 one_ 镜像未回填）→one.vector-index.*；004=indexChunks 同版本就地覆盖→PG 旧向量行不可达→恒 COUNT_MISMATCH→**deleteVectorsForVersion 前置清理**；越权 **335 格（285+50）LEAK=0**；生命周期 **49/49**；bad-case **12 条**（质量型 3 族 17/10/2 题+阻断已修 4+流程/资产/环境 5）UNATTRIBUTED=0 |
| D43 冻结参数 | datasetHash=75cc8d83cfb2…（20 题）；baseline 20×17；denyCode 4030；**idAnchor=50c457b6762a…（D43 ID 面）contentAnchor=c377bb2f62d7b71b4dfa3f87d47722b7bcdc989aaf4b218c607f8e7a9b6d64fb（V2 口径）**；retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3}；regressionExemptCols={timing,recordedAt}；d41Audit=195/30/0/0 |
| D43 纪律遗产 | C05 三段定界（39 资产污染→20 tombstone 窗口→0）；评测=disabled 干净实例+seeding 错峰；runDir 按 runExit 时序锚定；**重启预算 8/8 耗尽**；长 eval 脚本禁 Select -First 截管道；**OBS-D43-X1 外部并行实例**（IDEA jdwp PID 31008 监听 18080）共享 ai_db+PG 写噪声→审计差值口径+停机建议；A6 过期 token 未注入；publish 端点参数形态定界未做 |
| D43 可测性边界 | **C12：mock 态 sources 恒空**；citations=0 不误放行已证；模型级引用验证 NOT RUN |
| 测试现状 | D44 src/test **35 文件已齐**：22 Java（21 测试类含 **PromptTemplateControllerGuardTest/OneVectorIndexControllerGuardTest**（FIX-002/003 产物）+rerank 5+sync 10+RecoverStuckTasksScope+table 4+D44TableFixtureGenerator）+front-end-safety.test.js（6 用例）+application-test.yml+夹具 9 件（d44-* 前缀+FIXTURE-SHA256.txt+fixture-truth.json）；**继承基线=110/110** |
| 存储 | PG **d44_vector_store**（initialize=true 新表预计 0 行）+one_d44_vector_store（one_ 默认关）；MySQL d44_ 表（document_sync/admin_audit/auth_principal 等，P0 幂等 DDL）+共享 knowledge_document/knowledge_document_page/document_chunk_index/document_vector_index；遗留冻结文档集+chunk_other 32 行+d32eval 24 行 |
| 配置 | hybrid expected 默认 **10fc69d5…**（前代锚，启用须 env 注入 D44 锚）；access 默认 false/deny 4030；rerank 默认 noop 关（not-adopt 保留态，REST 通道 D-EX8）；table.enabled 默认 true/ocr 恒 false；fault-injection/sync-retry 默认 false；AI_MODE=mock；embedding local-hashing 384/COSINE；chunk 500/100 |
| 评测资产 | temp_docs\D37\evaluator\src\d37eval\ 9 源；数据集 20 题；SelfTest 75/75；FailureLabels 决策/质量轴；PGVECTOR 后端直连 PG 不经 app（D-EX6）→双通道证据分列 |
| 迭代演进（里程碑素材） | D25 DKDCM_RAC/D26 DIABP/D27 PDF_WORD/D28 TCASS/D29 EBD/D30 pgvector/D31 VSUAI/D32 SSATK/D33 CAARMCL/D34 ABONEARTAWE/D35 RTARC/D36 SPCE/D37 RAG_ESAB/D38 QWE_MROQRE/D39 KAAMF/D40 DUDACR/D41 TASIIEM/D42 RERANK/D43 RAG_ETEPAR（各代 commit/证据索引 KP3 汇编） |
| 可复用脚本 | temp_docs\D43\（lifecycle/security-matrix/badcase-classify/c05 回归/seeding/cleanup 全套）；temp_docs\D42\d42_index-corpus.ps1；temp_docs\D41\r03\d41_r03_cell_diff.ps1；temp_docs\D39\d39_security-matrix.ps1 |
| 目录 | docs\evidence\D44 与 docs\temp_docs\D44 编制时**不存在**（本轮新建）；logs\D44 已存在；proj_docs 至 D43-02（**D44-01/02 编号可用**，无 D44 知识笔记） |
| 工具链 | Maven `D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`（JDK21，仓库 D:\SoftwareInstall\Apache\repository）；PowerShell 7；`[Console]::OutputEncoding=UTF8`；**JDBC 必用 postgresql-42.7.11.jar**（42.1.4 SCRAM 不支持，禁通配）；PG 用户 postgres 库 spring_ai；MySQL root/ai_db；密码全走 env；无 mysql CLI（jshell/JDBC 先例） |

### 1.2 执行时复核命令（PowerShell 7，逐条留痕）

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-one\M3-Java"
git -C $r rev-parse HEAD; git -C $r status --porcelain   # 须 ea024a5；D44 骨架未提交→P0 先提交
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "$r\D44\pom.xml" -pl D44_RAG_PDAM test  # 继承基线须 110/110
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
(Get-FileHash "$r\docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl" -Algorithm SHA256).Hash  # 须 75cc8d83cfb2 开头
Get-NetTCPConnection -LocalPort 18080 -ErrorAction SilentlyContinue  # 外部实例噪声源检测（OBS-D43-X1）
# 应用起来后基线端点（端口 8080；Result.code 判成败，HTTP 恒 200；拒绝=4030）
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/documents/DOC-CNC-COOLING-001/pages" -TimeoutSec 5 | ConvertTo-Json -Depth 4
```

### 1.3 探测门禁（P0 裁决，证据落 `docs\evidence\D44\probe\`）

| 门禁 | 待核实项 | 方法 | 通过判据 | 失败处置 |
|---|---|---|---|---|
| KP1 | 冻结清单+主链盘点 | 骨架先提交（冻结锚 commit）；352 main Java 逐文件 SHA256→freeze-manifest.json；20 控制器端点+权限分级实扫；MySQL/PG 表字典素材盘点 | manifest 生成；端点/表清单与源码一致；继承 110/110 复跑 | 测试红→登记请示（H1 冻结期禁修）；清单不符→以实扫为准 |
| KP2 | 存储盘点+seeding+双锚+噪声源 | JDBC（42.7.11）盘点 d44_vector_store（预计 0）/d44_ MySQL 表/共享表遗留清单；幂等 DDL；seeding 8 片段（D43 通道+activate 步骤核实）；**D44 ID 面新锚实测+内容面 c377bb2f 恒等**；18080 外部实例检测 | 双锚冻结；内容面恒等；遗留清单冻结；噪声源状态记录 | 内容面不等→CORPUS_DRIFT 熔断；外部实例活跃→停机请示或差值防噪 |
| KP3 | 冻结资产+证据索引核验 | 数据集 SHA/20 题/baseline 20×17/SelfTest 75/75；D41 真值 SHA；**D25–D43 各代 commit+证据路径索引汇编**（里程碑素材） | 资产无损；索引齐全可追溯 | SHA 漂移→停止；索引缺口→如实标注 |
| KP4 | 答辩协议冻结 | 四能力断言口径（§3.2–3.5）；演示脚本步骤；评测错峰纪律+**重启预算预分配（≤6）**；里程碑验收标准；优化清单字段 schema | defense-protocol.json 冻结，先于执行 | 协议冲突→先裁决冻结 |

---

## §2 分阶段实施要求

### P0 骨架提交+冻结清单+探测（冻结锚定）

提交 D44 骨架（冻结锚 commit）；KP1–KP4；freeze-manifest.json+defense-protocol.json 冻结；seeding+双锚。

### P1 四关键能力回归（脚本 temp_docs\D44，真实 HTTP+服务级断言）

1. `d44_verify_citation.ps1`+观测单测：三层结构断言（§3.2）+边界声明。
2. `d44_verify_deletion.ps1`：删除闭环/旧版清理/TABLE_ORPHAN/重复幂等/FIX-004 回归/sync COUNT 守卫。
3. `d44_verify_access.ps1`（D43 矩阵模式）：335 格复跑+guard 回归+**A6 过期 token 补口**+审计差值。
4. `d44_verify_refusal.ps1`：无证据/检索失败/澄清受控问题集→决策码+零模型调用断言。

### P2 评测重跑+关键回归（D43 纪律全套）

1. C05：disabled 干净实例+seeding 错峰→20 题×3 轮→质量列 diff=0（20/60 双口径）；runDir 时序锚定。
2. D41 R03 复验 225 格 **195/30/0/0**；rerank 默认关零差异+opt-in 冒烟（not-adopt 保留态）。
3. 重启预算计数（KP4 帽）；耗尽即停。

### P3 文档答辩四件套+里程碑（全部从实扫生成，H9）

1. **架构图**：Mermaid graph TD 全景（六域主链+双存储+权限代数+同步状态机+实验分支）+六域分解图；Typora 0.11.18 兼容（subgraph ID [标题]+节点英文双引号）；落 D44-02+evidence\architecture\。
2. **API 说明**：20 控制器端点清单（方法/路径/权限分级/请求响应契约/错误码）；落 D44-01 附录+evidence\api-inventory.md。
3. **数据说明**：MySQL/PG 表字典（列/键/关联/生命周期/前缀隔离）；落 D44-01 附录+evidence\data-dictionary.md。
4. **文档库演示**：`d44_demo.ps1` 可复现全生命周期（mock 零远程），输出落 evidence\demo\。
5. **RAG v3 里程碑报告**：D25–D43 演进全景表+验收能力矩阵+已知边界+成本汇总+证据索引；落 evidence\milestone-report.md。
6. **失败案例文档**：D43 bad-case 12 条+FIX-001..004 根因卡+D42 D-EX1–10+D41 R03 机理修正，六域分类+证据索引；落 evidence\failure-cases.md。
7. **后续优化清单**：D43 遗留 9 项+D44 新观察，优先级/授权需求/预期影响；落 evidence\optimization-backlog.md。

### P4 清理+终态复验+双文档

1. 清理：PG/MySQL 前缀限定+FIXTURE_REMAINING=0+遗留零触碰+双锚恢复。
2. **冻结清单终态复验 drift=0**（H1 权威证明）；C05 复跑 diff=0。
3. `docs\proj_docs\D44-01_RAG阶段答辩与里程碑全量信息与操作.md`+`D44-02_RAG阶段答辩与里程碑技术文档与实践指南.md`（含架构图/演示指引/四能力验证方法/里程碑解读）。
4. 状态文件 `docs\evidence\D44\iteration_records\D44_state_R01.md`（§7 模板）。

---

## §3 工程深度专项

### 3.1 冻结语义与 manifest（H1）

- 冻结对象=src/main 352 Java+既有测试语义；freeze-manifest.json={file,sha256}×352+测试清单+冻结锚 commit。
- 终态复验：逐文件重算 SHA→drift 清单必须为空；新增文件（观测单测/脚本）不在 manifest 内但须登记 added-files.json。
- 冻结期发现阻断：登记 fix-registry（沿 D43 格式）→**停止请示**，不得静默修复（与 D43 修复迭代的本质区别）。

### 3.2 引用真实三层结构断言（D2，mock 边界诚实）

| 层 | 断言 | 证据 |
|---|---|---|
| 服务级 | CitationVerificationService 受控输入：真实 chunkId→PASS；伪造→FABRICATED_CITATION；已删→CITATION_TARGET_DELETED | 观测单测+受控 DB 夹具 |
| 在线级 | mock ask citations=0 不误放行（D43 C12 口径复验）；响应契约字段齐全 | HTTP 响应 jsonl |
| 数据级 | 引用 chunkId 与 document_chunk_index/PG 行存在性对账 | JDBC 对账记录 |

**边界声明**：mock 态 sources 恒空，模型级「引用真实」NOT RUN；真实臂须授权+预算，列优化清单。不得宣称「引用真实已端到端验证」。

### 3.3 删除同步复验（D3）

删除闭环四步（标记→清向量→清关联→双端确认）+UPDATE_CLEANUP 旧版清理+TABLE_ORPHAN 对账+重复事件幂等（索引与状态不变）+**FIX-004 回归**（同版本就地覆盖→重建→COUNT 一致）；sync 计数 T0 快照→delta 差值口径（外部噪声防扰）；失败路径走 6 原因码（受控注入复用 FaultInjecting 开关）。

### 3.4 权限过滤复验（D4）

主矩阵 285 格+one_ 臂 50 格复跑 LEAK=0；FIX-002/003 guard 单测回归；**A6 过期 token 夹具补口**（D43 缺口）；检索谓词在线探针（跨部门文档不可见）；审计 DENIED/ALLOWED 差值取证；disabled 态 guard 缺席零差异。

### 3.5 无证据拒答复验（D5）

受控问题集：无证据题（语料外问题）/检索失败（FaultInjecting 受控）/澄清题→断言决策码正确+**零模型调用**（mock 计数）+七态双轴语义不回退；与 D37 冻结 labels 分布对照（REFUSAL_FALSE_NEGATIVE 10 题/HIGH_NOISE 17 题/第三族 2 题为已知质量 FAIL，只记录不修）。

### 3.6 继承教训清单（D42–D43 踩坑，答辩必须规避）

- **评测纪律**：disabled 干净实例+seeding 错峰（tombstone 瞬态窗口）；runDir 时序锚定非字典序；长 eval 脚本禁 Select -First 截管道；重启预算预分配。
- **JDBC**：postgresql-42.7.11.jar 精确版本（42.1.4 SCRAM 报 Unsupported auth type 10）；spring_ai 是库名非用户。
- **外部噪声**：18080 jdwp 实例共享库写入→COUNT 守卫+差值口径+binlog 取证；停机须裁决。
- Result 包装 HTTP 恒 200：以 code 判成败；拒绝=4030；空检索≠失败；分母 0 标 N/A。
- ask strategy=metadata 等值过滤键（D-EX7）；评测器直连盲点双通道分列（D-EX6）。
- 30 unaligned 是真值勘误非缺陷——禁改解析代码或真值文件。
- runExit=1=冻结基线已知业务 FAIL 契约；评测器全量 javac 重编；javac -encoding UTF-8。
- temp_docs 被 git 忽略，源码 git add -f 先例；token JSON/夹具明文不提交；logs *.log/*.err 不入库。

---

## §4 验收标准（L1–L5 + C01–C20 + 熔断）

### 4.1 分级定义

| 级 | 内容 | 本轮要求 |
|---|---|---|
| L1 | 骨架提交/继承 110/110/前端 6/6/冻结清单 | 必须 PASS |
| L2 | 门禁/DDL/seeding 双锚/协议冻结 | 必须 PASS |
| L3 | 四能力复验/评测重跑 diff=0/关键回归 | 必须 PASS |
| L4 | 答辩四件套/里程碑/失败案例/优化清单/清理/终态 drift=0 | 必须 PASS |
| L5 | 跨机复现 | NOT RUN（单机），如实标注 |

四态逐用例落表，计数合计=用例总数；N/A 须写原因。

### 4.2 逐条用例（证据路径相对 `docs\evidence\D44\`）

| 编号 | 用例 | PASS 判据 | 证据 |
|---|---|---|---|
| C01 | 骨架提交+继承 | 冻结锚 commit；mvn test **110/110** 不回退；前端 6/6 | compile-test.txt + git\ |
| C02 | KP1–KP4 | 四门禁裁决留痕；manifest+protocol 冻结 | probe\ |
| C03 | 冻结零漂移 | 终态 352 文件 SHA 复验 **drift=0**；added-files 登记齐全 | freeze-manifest.json + freeze-verify.json |
| C04 | DDL+seeding+双锚 | d44_ 表幂等×2；8 片段；ID 新锚+内容面 c377bb2f 恒等 | probe\kp2-* + ddl\ |
| C05 | 评测重跑 | 20 题×3 轮质量列 **diff=0**（豁免列口径）；20/60 双口径；错峰纪律留痕 | evaluation\ |
| C06 | 引用真实 | 三层结构断言全 PASS+**边界声明**（模型级 NOT RUN） | verify\citation-*.json |
| C07 | 删除同步 | 闭环/旧版/TABLE_ORPHAN/幂等/FIX-004 回归全 PASS；COUNT 守卫 | verify\deletion-*.jsonl |
| C08 | 权限过滤 | 335 格 LEAK=0；guard 回归；**A6 过期 token 补口**；审计差值 | verify\access-*.jsonl |
| C09 | 无证据拒答 | 受控问题集决策码正确+零模型调用；labels 分布与冻结一致 | verify\refusal-*.json |
| C10 | 架构图 | 全景+六域 Mermaid；节点/连线与实扫一致；Typora 渲染验证 | architecture\ |
| C11 | API 说明 | 20 控制器端点全清单+权限分级+契约；与源码一致 | api-inventory.md |
| C12 | 数据说明 | MySQL/PG 表字典与 DDL/PO 一致 | data-dictionary.md |
| C13 | 文档库演示 | d44_demo.ps1 可复现；全链节输出落证据；mock 零远程 | demo\ |
| C14 | 失败案例文档 | 12 bad-case+4 FIX+D-EX+R03 汇总；六域+证据索引齐全 | failure-cases.md |
| C15 | 优化清单 | 9 项继承+新观察；优先级/授权需求标注 | optimization-backlog.md |
| C16 | 里程碑报告 | D25–D43 演进全景+能力矩阵+边界+成本+证据索引 | milestone-report.md |
| C17 | D41 R03 复验 | 225 格 **195/30/0/0** 不回退；wrong_value=0 | regression\d41-cell-audit.json |
| C18 | rerank 保留态 | 默认关零差异；opt-in 冒烟；远程 NOT RUN | regression\rerank-smoke.json |
| C19 | 清理+回归复验 | FIXTURE_REMAINING=0；遗留零触碰；双锚恢复；C05 再 diff=0 | cleanup\ + regression\ |
| C20 | 卫生+预算+文档+Git | sk-/Bearer 零命中；DeepSeek 0/百炼 0/远程 rerank 0；重启≤帽；D44-01/02+状态文件；提交核验不 push | security\ + cost\ + git\ |

### 4.3 四能力受控验证矩阵（禁破坏性操作）

| 编号 | 能力 | 受控触发 | 验收标准 |
|---|---|---|---|
| A1 | 引用真实 | 服务级构造真实/伪造/已删 chunkId 引用 | 三类分类正确；在线 citations=0 不误放行；边界声明 |
| A2 | 删除同步 | 夹具文档删除+同版本重建（FIX-004 场景）+重复删除事件 | 双端确认归零；COUNT 一致；幂等不变 |
| A3 | 权限过滤 | 匿名/普通/跨部门/**过期** token×读写面 | 4030 fail-closed；LEAK=0；审计差值一致 |
| A4 | 无证据拒答 | 语料外问题+FaultInjecting 检索失败+澄清题 | 决策码正确；零模型调用；七态不回退 |

### 4.4 熔断条件（命中即停，写状态文件后报告，不得绕过）

1. 同一根因连续失败 2 次 → 停止该链路，持久化现场，等待人工确认。
2. **冻结清单 drift≠0（业务代码被改）→ 立即停止**，回滚或请示（H1 红线）。
3. C05 质量列 diff≠0（排除豁免列与 tombstone 窗口后）/ 内容面指纹漂移 / 遗留被触 → 停止，先定界（D43 三段定界法）。
4. 冻结期发现阻断缺陷需改码 → **登记+停止请示**，不得静默修复。
5. 外部实例噪声不可控（差值口径仍漂移）→ 停止评测窗口，停机请示。
6. **重启预算耗尽 / 远程调用计数 >0** → 立即停止（H5/零远程红线）。
7. 把结构断言宣称为模型级引用验证 / 答辩材料与实扫不符 → 诚实性缺陷，立即停止修正。
8. 基线演进/真值勘误/质量修复未经裁决而执行 → 违反 H2，立即停止。

---

## §5 成本与预算（不伪称预算）

| 项 | 规则 |
|---|---|
| 全部测试 | local Embedding 零网络；生成走 mock；one_/hybrid/rerank remote 默认关 → **DeepSeek 0、百炼 0、远程 rerank 0、Token 0**；以模式配置+开关状态+零远程计数证明 |
| 重启预算 | KP4 预分配 **≤6 次**（D43 8/8 耗尽教训）；每次重启登记原因；耗尽即停 |
| 计算资源 | 评测 20 题×(warmup3+rounds3)；矩阵 335 格；演示单链；计时 nanoTime |
| 凭据管理 | PG_VECTOR_PASSWORD/DASHSCOPE_API_KEY/AI_API_KEY 仅环境变量（只验存在不回显）；JDBC 驱动 postgresql-42.7.11.jar 精确引用；夹具 token 明文仅 temp_docs\D44\（不提交） |

---

## §6 交付物清单与 Git 规则

| # | 交付物 | 路径 |
|---|---|---|
| 1 | RAG v3 里程碑报告 | docs\evidence\D44\milestone-report.md |
| 2 | 评测报告（重跑+基线对照） | docs\evidence\D44\evaluation\ |
| 3 | 架构图（全景+六域） | docs\evidence\D44\architecture\ + D44-02 |
| 4 | 失败案例文档 | docs\evidence\D44\failure-cases.md |
| 5 | API 说明+数据说明 | docs\evidence\D44\api-inventory.md + data-dictionary.md |
| 6 | 文档库演示脚本+输出 | docs\temp_docs\D44\d44_demo.ps1 + evidence\demo\ |
| 7 | 后续优化清单 | docs\evidence\D44\optimization-backlog.md |
| 8 | 四能力验证证据+冻结清单+终态复验 | docs\evidence\D44\verify\ + freeze-*.json |
| 9 | 回归/清理/卫生证据 | docs\evidence\D44\{regression,cleanup,security,ddl,probe}\ |
| 10 | 两份技术文档 | docs\proj_docs\D44-01_…全量信息与操作.md、D44-02_…技术文档与实践指南.md |
| 11 | 迭代状态文件 | docs\evidence\D44\iteration_records\D44_state_R01.md |
| 12 | 运行日志 | logs\D44\（*.log/*.err 不入库，权威证据=jsonl） |

Git：**P0 先提交 D44 骨架（冻结锚）**；迭代提交仅含新增文档/脚本源码（temp_docs 被忽略，git add -f 先例，token JSON 不提交）/观测单测/evidence\D44/proj_docs 两份/状态文件；**src/main 与既有测试零改动（C03 权威证明）**；D37/D39–D43 资产与遗留行零改动；提交前 `git diff --cached --stat` 核验；提交信息 `D44 项目迭代R01 RAG阶段答辩与里程碑`；不 push。临时文件一律不自动删除。

---

## §7 状态文件模板（D44_state_R01.md）

```markdown
# D44_state_R01 —— RAG 阶段答辩与里程碑（one 仓）
- 迭代/切片：D44 / R01；冻结锚 commit：<P0 骨架提交>（父=ea024a5，执行时复核）；本切片交付 commit：<git>
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / PG 18.7+pgvector 0.8.7 / MySQL 8
- 冻结参数：datasetHash=75cc8d83cfb2…（20 题）baseline=20×17 denyCode=4030 idAnchor=<KP2 D44 新锚> contentAnchor=c377bb2f62d7b71b…（恒等实证）retrievalParams={topK:5,threshold:0.0,warmup:3,rounds:3} regressionExemptCols={timing,recordedAt} d41Audit=195/30/0/0 restartBudget=<KP4 ≤6> freezeManifest=352 files
- 偏差裁决：D1 冻结=manifest+drift=0 / D2 引用真实三层结构断言+mock 边界 / D3 删除同步+FIX-004 回归 / D4 权限 335 格+A6 补口 / D5 拒答零模型调用 / D6 架构图实扫生成 / D7 可复现演示 / D8 优化清单 9+新 / D9 里程碑+失败案例汇总 / D10 评测纪律继承（执行时确认）

## 门禁与用例落表
| 编号 | 状态(PASS/FAIL/BLOCKED/NOT RUN/N/A) | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | … | probe\… | … |
| C01–C20 | … | … | … |
| A1–A4 | … | verify\… | 触发+处置证据 |

## 分级结论
L1=… L2=… L3=… L4=… L5=NOT RUN（单机）

## 答辩结论
- 冻结 drift=0；四能力验证状态（引用真实=结构断言+边界声明）；评测 diff=0；里程碑/失败案例/优化清单交付状态

## 成本实耗
DeepSeek calls=0 tokens=0（mock）；百炼 0；远程 rerank 0；重启 N/≤6；夹具 FIXTURE_REMAINING=0；双锚恢复

## 诚实声明
- 已执行并通过：… / 已执行但失败：… / 尚未执行：…（模型级引用验证/真实臂/跨机）/ N/A 项及原因：…

## 遗留与后续（优化清单索引）
质量型修复（须裁决）/ D41 真值勘误（须裁决）/ 真实模型臂引用验证（须授权）/ hybrid+rerank 组合臂 / 词面弱相关新数据集 / cross-encoder 本地模型 / sync 终态行保留策略 / seeding activate 步骤 / 外部实例停机（须裁决）/ 跨机 L5 / D44 新观察
```

---

## §8 官方文档溯源（执行时复核，不锁定记忆值）

- 《Spring AI Reference — Retrieval Augmented Generation | Spring AI 检索增强生成》：https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html （主链架构与 ETL 依据）
- 《Spring Boot Reference — Testing | Spring Boot 测试》：https://docs.spring.io/spring-boot/3.5/reference/testing.html （集成观测与 profile 注入依据）
- 《pgvector — README | pgvector 向量检索》：https://github.com/pgvector/pgvector （PG 表字典与相似度口径依据）
- 《PostgreSQL JDBC Driver — Documentation | PG JDBC 驱动》：https://jdbc.postgresql.org/documentation/ （42.7.x SCRAM 认证支持依据，本机必用 42.7.11）
- 《Mermaid — Flowchart Syntax | Mermaid 流程图语法》：https://mermaid.js.org/syntax/flowchart.html （架构图 Typora 兼容语法依据）
- 本地权威依据：**D43 R01 状态与修复登记**（docs\evidence\D43\iteration_records\D43_state_R01.md、fixes\fix-registry.jsonl、badcase-classification.csv、e2e-report.md）、D42 状态与 D-EX（docs\evidence\D42\iteration_records\D42_state_R01.md）、D41 R03 状态（docs\evidence\D41\iteration_records\D41_state_R03.md）、D37 冻结资产（docs\evidence\D37\）、D44 骨架实扫（§1.1）。

> 溯源纪律：不得伪造文档地址/源码内容/指标/验证结果；无法确认的信息标「待运行时核验」。本任务书所有实测值来自编制时真实扫描，执行时若漂移，以执行时实读为准并在状态文件记录差异。冻结零改动、边界诚实声明、答辩材料可复算——绝不把结构断言冒充模型级验证，绝不静默修复冻结期缺陷。