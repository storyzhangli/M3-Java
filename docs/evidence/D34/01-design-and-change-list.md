# D34 阶段二：修改清单与设计方案

> 依据：本地真实源码精读 + 00-env-probe.txt。所有阈值均为可配置项，未硬编码承诺值。

## 1. 新增文件（主代码）

### 1.1 生命周期（document.model / document 资源）
- `document/model/DocumentLifecycleStatus.java`：DRAFT（未生效）/ ACTIVE（生效）/ DEPRECATED（废弃）
- 资源：`resources/db/schema-d34-lifecycle.sql`（ALTER 增加 lifecycle_status 列，附存在性判断；非自动执行）

### 1.2 证据评估（新包 rag.assessment）
- `AssessmentDecision.java`：ANSWER / CLARIFY / REFUSE / CONFLICT
- `FactClaim.java`（record：subject/parameter/value/sourceRef）
- `FactClaimExtractor.java`：从正文确定性抽取 (主体, 参数, 值)，隔离启发式脆弱性
- `ConflictGroup.java`（record：parameter/values/evidenceIds）
- `DocumentLifecyclePort.java`：按 documentId 解析生命周期（端口）
- `DefaultDocumentLifecyclePort.java`：基于 KnowledgeDocumentMapper 的实现
- `EvidenceAssessmentResult.java`：9 字段（decision/reasonCode/reason/evidenceIds/acceptedEvidenceIds/rejectedEvidenceIds/conflictGroups/missingInformation/assessmentDetails）
- `EvidenceAssessmentService.java`：不调模型、不触发检索

### 1.3 生成后校验（新包 rag.validation）
- `CitationValidationResult.java`（record：legalCitationPassed/unsupportedClaims/invalidCitations/validationPassed）
- `ResponseValidationService.java`：区分引用 ID 合法性与事实支持关系

## 2. 修改文件（主代码，最小改动）

| 文件 | 改动 |
|---|---|
| document/model/KnowledgeDocument.java | 增加 lifecycleStatus 字段 |
| document/po/KnowledgeDocumentPO.java | 增加 lifecycleStatus 列映射 |
| document/support/DocumentConverter.java | 同步 lifecycleStatus 双向映射 |
| resources/db/schema-knowledge-document.sql | 新表定义补 lifecycle_status 列 |
| rag/model/RagErrorCode.java | 新增 7 个原因码（LOW_RELEVANCE/INSUFFICIENT_EVIDENCE/MISSING_REQUIRED_CONTEXT/EVIDENCE_CONFLICT/INVALID_CITATION/UNSUPPORTED_CLAIM/GENERATION_VALIDATION_FAILED） |
| rag/model/GenerationStatus.java | 新增 CLARIFY、CONFLICT |
| rag/context/EvidenceItem.java | 增加 lifecycleStatus 字段 |
| rag/context/ContextAssemblyService.java | 证据块改 UNTRUSTED_EVIDENCE 标记 |
| rag/prompt/RagPromptBuilder.java | 保留原 6 条，新增 7–11（三区分/澄清/注入防护/受控决策/安全引用） |
| rag/dto/RagAnswerResponse.java | 增加 decision/clarificationQuestion/missingInformation/conflictGroups/rejectedEvidenceIds/citationValidation |
| rag/dto/ConflictGroupView.java、CitationValidationView.java（新增 DTO） | 响应子结构 |
| rag/orchestrator/RagOrchestrator.java | 插入评估环节，按决策路由；生成后校验失败受控返回 |

## 3. 评估口径（确定性规则，可解释）

1. 生命周期：DEPRECATED/DRAFT 一律拒绝进入有效证据；ACTIVE 保留。
2. 冲突：仅在同主体（同设备/同主题）、同参数、不同值且来自不同 documentId 时判冲突；不同设备型号不判冲突。
3. 充分性：先生命周期筛选，再结合可配置相似度信号与关键词覆盖；相似度仅作信号，不单独决定。
4. 无证据/空上下文：不调模型，保留零调用可核验证据。
5. 事实支持：回答中参数/故障码/维修步骤类断言必须能在有效证据中找到同参数同值，否则 UNSUPPORTED_CLAIM。

## 4. 配置外置（新增 app.rag.assessment）

- lowRelevanceSimilarityThreshold（默认值仅为起点，非实测承诺）
- requireDeviceModel / requireFaultCode（澄清触发开关）
- 真实密钥仍仅经环境变量；不新增依赖、不改 384 维、不引入重排序/混合检索。

## 5. 高风险点

1. 真实 MySQL 加列：非破坏性 ADD COLUMN，默认 ACTIVE，不影响 D33（额外列被忽略）；执行前系统会确认。
2. 启发式抽取仅覆盖结构化参数表达，复杂自然语言不在本次范围，文档如实说明。
3. Level 5 仍需真实 ChatModel（AI_MODE=deepseek/AI_API_KEY），Mock 不冒充。
