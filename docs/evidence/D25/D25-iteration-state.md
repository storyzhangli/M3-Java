# D25 迭代状态

> 本文件依据实际文件、命令输出与测试结果维护，不从历史对话回忆补写。
> 状态取值仅使用：通过 / 失败 / 未验证 / 不适用（附理由）。路径均相对工程根 `M3-Java`。

## 0. 迭代总览

| 项 | 值 |
|---|---|
| 迭代名称 | D25：RAG 架构与评测设计 |
| 模块 | D25/D25_DKDCM_RAC（物理包 com.zl.d25） |
| 性质 | 架构定义与评测基线设计，非完整 RAG 功能开发 |
| 技术基线 | JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、Spring Cloud 2025.0.0、MyBatis-Plus 3.5.17、Maven 3.9.16 |
| 真实语料 | 3 文档 / 8 片段（equipment-fault-corpus.json） |
| 评测数据集 | 22 条固定用例，格式校验通过 |
| 全量构建测试 | 123 测试全通过、退出码 0 |
| 业务代码改动 | 无（本轮仅新增设计/证据/文档，未改任何主源码与依赖） |

## 需求编号
D25-R01（KnowledgeDocument/Chunk 元数据与 RAG 边界）、D25-R02（20 条起步评测问题集）。

## 新增/修改文件
- 证据（新增，docs/evidence/D25）：
  - D25-knowledge-document-schema.md（KnowledgeDocument 字段字典）
  - D25-chunk-schema.md（Chunk 字段字典）
  - D25-rag-module-boundary.md（10 职责边界、分层依赖、Mermaid）
  - D25-rag-evaluation-dataset.yaml（22 条固定用例）
  - D25-validation-summary.txt（校验结果）
- 临时（docs/temp_docs/D25，不自动删除）：D25-baseline.md、D25-code-index.md、validate_dataset.py
- 正式文档（docs/proj_docs）：D25-项目迭代内容与操作记录.md、D25-RAG架构与评测实践指南.md
- 主源码/依赖：零修改。

## 设计决定
- 复用现有 knowledge 包契约（KnowledgeChunk 8 字段、CitationRegistry、KnowledgeChatOrchestrator），
  新设计字段与现有同名字段对齐，新增字段统一标“规划项”，不把示例字段写成已存在字段。
- KnowledgeDocument 承载业务身份/来源/版本/权限/生命周期；Chunk 为检索单元，强制继承父文档权限、默认拒绝。
- 权限裁决由独立鉴权能力负责且在证据暴露前完成；当前项目无鉴权，权限字段与用例为目标设计，不宣称已生效。
- 供应商 SDK/向量库实现限制在基础设施层，领域与业务接口只依赖端口抽象。
- 故障码：语料无显式数字码，仅文字报警；无码类用例设计为 abstain/clarify，禁止编造或跨设备套用。
- 不接入向量库/Embedding/解析器，不建表、不新增依赖；指标在真实运行前不填数值。

## 评测数据
- 用例总数 22（≥20）；类别分布：answerable 7、fault-code 4、unanswerable 4、permission 4、boundary 3。
- 四类强制场景（有答案/无答案/权限/故障码）均覆盖；caseId 唯一、必填字段齐全、枚举合法。
- 来源状态：真实资料支持 13、当前缺少资料 6、待业务确认 3（权限夹具）。
- 数据来源：仅 equipment-fault-corpus.json；无真实来源的用例标注待补，不虚构文档/页码/Chunk。

## 校验/测试
- 命令：`python docs/temp_docs/D25/validate_dataset.py` → 结果：通过（YAML 解析成功、ID 唯一、必填齐全、覆盖完整）。
- 命令：`mvn -f D25/D25_DKDCM_RAC/pom.xml clean test` → 结果：通过。Tests run: 123，Failures/Errors/Skipped 均 0，BUILD SUCCESS，退出码 0。
- 未执行项：真实 RAG 端到端（向量/Embedding/重排）未实现，不宣称端到端通过；真实模型 remote 无 AI_API_KEY，未验证。

## 证据
- 设计/数据：docs/evidence/D25 下 5 份文件；日志：logs/D25/full-test.log；校验：D25-validation-summary.txt。

## 遗留问题
- 无构建阻塞。
- 权限用例的主体/范围为测试夹具，实际角色/租户映射与鉴权强制力待业务确认与后续落地。
- 显式故障码、品牌清单、液压/机器人等资料缺失，相关用例已标“当前缺少资料”，待补资料后再评测。
- 命中率/Recall/MRR 等指标待真实 RAG 运行后按统一口径计算，当前不填数值。
