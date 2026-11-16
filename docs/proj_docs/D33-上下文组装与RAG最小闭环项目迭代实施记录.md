# D33 上下文组装与 RAG 最小闭环项目迭代实施记录

> 模块：D33 / D33_CAARMCL
> 物理包：com.zl.d33
> 技术基线：Spring Boot 3.5.16、Spring AI 1.1.8、PostgreSQL 18.7 + pgvector 0.8.7、JDK 21
> 实施日期：2026-11-16
> 前置：D32 相似度检索与 Top-K 已完结（Level 4）

---

## 一、问题结论（先看结论）

本次迭代在不升级依赖、不改 384 维 Embedding、不引入重排序/混合检索的前提下，新增最小 RAG 闭环：

```
文档导入 → Chunk 切分 → Embedding 入库 → 用户问题 → Query Embedding
        → Top-K 检索 → 上下文组装 → Prompt 构造 → ChatModel 生成 → 回答与来源返回
```

- 新增主代码 **20 个文件**（位于 `com.zl.d33.rag`），离线整体编译通过（exit 0）。
- 新增测试 **7 个测试类、33 个测试用例**：真实执行 **29 个通过**，**4 个真实集成测试因当前会话缺少 PG 密码环境变量而干净禁用**（非冒充通过）。
- 四类端到端场景（文档可答、无关问题、空检索不调模型、模型超时）均以受控方式验证，空检索/检索失败/生成失败三类状态严格区分。
- 上下文预算以 **UTF-16 字符（code unit）** 为单位，明确不伪称 Token。
- 当前可达成 **Level 4（受控端到端）**；**Level 5 真实 PG + 真实 ChatModel 端到端**待用户提供凭据后执行，本记录不提前宣称。

---

## 二、目标与范围

### 2.1 目标

1. 复用 D25–D32 已建成的文档导入、切分、Embedding、向量入库、Top-K 检索能力。
2. 新增上下文组装（有效性/去重/顺序/预算/来源映射）、Prompt 构造、生成编排与 HTTP 入口。
3. 保证证据可追溯、上下文预算可解释、异常受控，且各服务职责边界清晰。

### 2.2 范围边界（最小改动）

- 只新增 `com.zl.d33.rag` 包及其测试，不修改既有检索/入库/模型基础设施的公共契约。
- 不升级依赖、不换 Embedding、不改 384 维、不做重排序/混合检索/外部知识、不引入第二套向量库。
- 不做完整 Agent/MCP/复杂多轮；不把生成结果写回知识库。
- 不执行 git commit/push、不做 Docker prune/删卷/重建容器。

---

## 三、架构与职责边界

### 3.1 分层结构

```
com.zl.d33.rag
├─ controller   RagController                HTTP 入口 POST /api/v1/rag/ask
├─ orchestrator RagOrchestrator              流程编排，不写检索/模型细节
├─ retrieval    RagRetrievalService          复用 SimilaritySearchService，区分 EMPTY/FAILED
│               RagRetrievalQuery/Result     内部查询与结果对象
├─ context      ContextAssemblyService       有效性/去重/顺序/预算/来源映射
│               ContextAssemblyResult/EvidenceItem
├─ prompt       RagPromptBuilder             集中维护系统规则与用户消息
├─ generation   GenerationService            Prompt + ChatModel，不访问 VectorStore/DB
│               GenerationResult
├─ dto          RagQuestionRequest/RagAnswerResponse/RagSourceView/RagBudgetView/RagTimingView
├─ model        RetrievalStatus/GenerationStatus/RagErrorCode
└─ config       RagProperties (app.rag)
```

### 3.2 四服务职责（任务书口径）

| 服务 | 单一职责 | 明确不做 |
|---|---|---|
| RagOrchestrator | 编排检索→组装→生成，汇总状态/来源/耗时 | 不写检索 SQL、不直接调模型、不拼 Prompt |
| RagRetrievalService | Query Embedding→VectorStore→Top-K，复用底层检索 | 不调用 ChatModel；不重复实现检索 |
| ContextAssemblyService | 有效性/去重/保序/预算/唯一来源映射/剔除统计 | 不调用模型、不做重排序 |
| GenerationService | 构造 Prompt 并调用 ChatModel，处理超时/异常/空响应 | 不访问 VectorStore/Repository/DB |

依赖方向：`controller → orchestrator → (retrieval / context / generation)`；
generation 只依赖 prompt 与 ChatModel，不反向依赖检索，保证边界不被穿透。

---

## 四、关键技术决策（均有本地证据）

### 4.1 上下文预算：字符口径，不伪称 Token

- 本工程无 Tokenizer，不具备真实 Token 计数能力，因此预算单位固定为 **UTF-16 字符（code unit）**，
  `RagProperties.budgetUnit = "CHARACTER"`，默认 `contextBudgetChars = 6000`，并为输出预留 `reservedOutputChars = 1000`。
- 组装按检索顺序逐块纳入，加入下一块会超预算时即停止，后续块全部标记 BUDGET；
  最终 `contextText` 长度保证不超过预算，不做静默截断、不破坏证据来源。
- 预算视图 `RagBudgetView` 明确写入估算说明：真实 Token 数随分词器变化，可能存在偏差，未伪称 Token。

### 4.2 证据来源可追溯

- 每个进入上下文的证据按顺序分配唯一标识 `[S1]、[S2]…`，并映射真实 chunkId/documentId/title/version/chunkIndex/strategy 及真实分数。
- 证据块统一渲染：
  ```
  [S1] 来源文档: <title> (documentId=<id>, version=<v>, chunkIndex=<n>)
  <content>

  ```
- 对外 `sources` 语义为“提供给模型的证据来源”，**不声称每条都被实际引用**。
- 生成后用正则 `\[(S\d+)]` 核验回答中的标识是否都在上下文内；出现上下文之外的标识则置 `unknownCitationDetected=true`，不伪造来源。

### 4.3 模型可选依赖：mock 模式可启动

- mock 模式下容器不存在 ChatModel Bean。GenerationService 以 `Optional<ChatModel>` 注入，
  需要生成但无模型时返回 `NOT_CONFIGURED / MODEL_NOT_CONFIGURED`，而非启动失败。
- 模型调用经 `ModelResilienceSupport.executeBlocking`，捕获 `ModelInvocationException`：
  `READ_TIMEOUT → FAILED_TIMEOUT / GENERATION_TIMEOUT`，其余异常 → `FAILED_ERROR / GENERATION_FAILED`；
  空响应 → `EMPTY_RESPONSE`。

### 4.4 检索状态三态区分

- `SUCCESS`：执行成功且有命中；`EMPTY`：执行成功但零命中；`FAILED`：执行异常。
- 编排器在 `FAILED` 时短路（不组装、不生成）；在 `EMPTY/无证据` 时拒答且不调用 ChatModel。
- 空检索与检索失败、检索失败与生成失败因此落在不同状态码，可被外部核验。

### 4.5 PG 密码环境变量差异（如实记录，不改配置）

- D33 `application.yml` 第 219 行真实占位为 `password: ${PG_VECTOR_PASSWORD:}`，即 D33 启动真实读取 **PG_VECTOR_PASSWORD**。
- `PgVectorStoreProperties` 的 Javadoc 写的是 PGVECTOR_DB_PASSWORD（D32 实际使用该名）。
- 结论：D33 以 application.yml 的 **PG_VECTOR_PASSWORD** 为准；本次不改配置，仅在文档注明，避免后续启动用错变量名。

---

## 五、各阶段实施记录

| 阶段 | 内容 | 结果 |
|---|---|---|
| Phase 1 | 只读扫描 D33 骨架、复用组件、缺口与配置差异 | 完成，落 `docs/evidence/D33/D33-工程扫描结果.md` |
| Phase 2 | RAG 分层架构与对象设计 | 完成 |
| Phase 3 | 编码 20 个主文件 | 离线 `mvn -o compile` exit 0 |
| Phase 4 | 单元/集成测试与门控 | 29 通过，4 集成禁用 |
| Phase 5 | 四类端到端场景与演示脚本 | 受控验证通过，交付 HTTP 演示脚本 |
| Phase 6 | 双文档 + 时序图 + 证据索引 | 本次交付 |

---

## 六、四类端到端场景结果

> 说明：当前会话无 PG 密码与真实模型凭据，以下为通过**真实编排/组装/生成/Prompt 服务 + 对外部边界打桩**的受控端到端（`RagFourScenarioDemoTest`），不是 L5 真实端到端。

| 场景 | 检索状态 | 生成状态 | 错误码 | ChatModel 调用 | 结论 |
|---|---|---|---|---|---|
| 1 文档可答 | SUCCESS | SUCCESS | NONE | 1 次 | 回答含真实 [S1]/[S2] 引用，证据 2 条 |
| 2 无关问题 | SUCCESS | SUCCESS | NONE | 1 次 | 受控回答“知识库证据不足”，不补造 |
| 3 空检索 | EMPTY | SKIPPED_NO_EVIDENCE | NO_EVIDENCE | **0 次（可核验）** | 受控拒答，不调用模型 |
| 4 模型超时 | SUCCESS | FAILED_TIMEOUT | GENERATION_TIMEOUT | 经韧性层抛超时 | 受控错误，answer=null，不伪装成功 |
| 补充 检索失败 | FAILED | NOT_ATTEMPTED | RETRIEVAL_FAILED | 0 次 | 与空检索、生成失败区分 |

“空检索不调用模型”的可核验证据：测试中对 Mockito 模拟 ChatModel 执行
`verify(chatModel, never()).call(any(Prompt.class))`，调用次数断言为 0。

---

## 七、测试验证（真实数字）

最终命令：`mvn -f D33/D33_CAARMCL/pom.xml -o test`，结果 **BUILD SUCCESS**。

| 测试类 | 用例数 | 通过 | 跳过 |
|---|---|---|---|
| ContextAssemblyServiceTest | 6 | 6 | 0 |
| RagControllerTest | 4 | 4 | 0 |
| RagFourScenarioDemoTest | 5 | 5 | 0 |
| GenerationServiceTest | 7 | 7 | 0 |
| RagRetrievalIntegrationTest | 4 | 0 | 4（缺 PG 密码） |
| RagOrchestratorTest | 5 | 5 | 0 |
| RagPromptBuilderTest | 2 | 2 | 0 |
| **合计** | **33** | **29** | **4** |

- 编译验证：`mvn -o compile` exit 0。
- 集成门控：`@EnabledIfPgVector` 同时判断 TCP 可达与 PG_VECTOR_PASSWORD 是否存在；
  密码缺失时干净禁用并说明原因，不把“跳过”当“通过”。

---

## 八、Level 分级评定（不夸大）

| 等级 | 口径 | 本次状态 |
|---|---|---|
| L1 | 依赖解析/编译/单测 | **已达成**：编译 exit 0，单测全绿 |
| L2 | 文档向量与检索复用 | **已达成（代码与门控就绪）**：真实集成测试待凭据执行 |
| L3 | 去重/排序/预算/来源映射 | **已达成**：单测覆盖并通过 |
| L4 | Generation/Prompt/拒答/模型异常 | **已达成**：受控端到端通过 |
| L5 | 真实 PG + 真实 ChatModel 四类端到端、来源可追溯 | **未达成（待凭据）**：Mock/受控测试不替代真实端到端 |

当前整体评定：**Level 4**。Level 5 需用户提供 `PG_VECTOR_PASSWORD` 与 `AI_API_KEY`，
以 `AI_MODE=deepseek` 启动后，运行 `D33_CAARMCL/demo/D33-rag-demo.ps1` 与集成测试，再据实更新。

---

## 九、时序图（关键分支）

完整图见 `docs/proj_docs/D33-RAG时序图.md`，核心分支：检索失败短路、空检索/无证据跳过模型、模型超时受控失败。

---

## 十、风险与后续事项

1. **Level 5 未执行**：真实 PG + 真实 ChatModel 端到端待凭据，禁止用受控测试冒充。
2. **Javadoc 与配置不一致**：PG 密码 Javadoc 写 PGVECTOR_DB_PASSWORD，真实为 PG_VECTOR_PASSWORD；本次仅注明，未改配置。
3. **字符预算的固有误差**：字符数不等于 Token，真实 Token 随分词器变化；当前口径保守可解释，已注明。
4. D32 人工相关性复核仍未完成，D32 维持 Level 4，与本次 D33 评定相互独立。
5. 真机启动需用户提供 PG_VECTOR_PASSWORD（D33 口径）与 deepseek 模式 AI_API_KEY，真实口令不持久化。

---

## 十一、证据索引

见 `docs/evidence/D33/D33-证据索引.md`。
