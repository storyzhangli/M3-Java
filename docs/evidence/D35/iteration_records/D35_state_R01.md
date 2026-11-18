# D35 迭代状态

## 需求编号
D35-R01：引用溯源与返回契约（DB 增强版）

## 阶段二设计决定（修改清单）

### 新增主代码（`com.zl.d35.rag.citation`）
- `UrlSafetyPolicy`：静态 URL 协议黑名单，仅放行 http/https 可信链接；拒绝 javascript/data/file/ftp、localhost、内网/环回地址、本机绝对路径；不安全返回 null。
- `SnippetExtractor`：从真实 content 确定性截取引用片段，UTF-16 安全（不切断代理对），默认上限 200 字符，模型不参与。
- `CitationVerificationService`：批量核验（documentId 一次 IN 查询 + chunkId 一次 IN 查询，避免 N+1），区分 OK / CITATION_TARGET_DELETED / EVIDENCE_NOT_EFFECTIVE；并负责 cited 标记与 url 安全解析。

### 修改主代码
- 入库：`DocumentVectorIndexService.buildMetadata` 补真实 page/lastPage/paragraphIndex（仅非空）。
- 检索：`SimilarityMatchView` 从 metadata 读 page/lastPage/paragraphIndex。
- 组装：`EvidenceItem` 增 page/lastPage/paragraphIndex/quotedSnippet/cited；`ContextAssemblyService` 映射并截取片段；`RagProperties` 增 quotedSnippetMaxChars=200。
- 响应：`RagSourceView` 类级 NON_NULL，增 sourceId/citationId/page/lastPage/paragraphIndex/quotedSnippet/cited/url；`RagAnswerResponse` 增 citationNotes。
- 错误码：末尾追加 CITATION_TARGET_DELETED、PAGE_LOCATION_MISSING、FABRICATED_URL、EVIDENCE_NOT_EFFECTIVE。
- 编排：`RagOrchestrator` 响应前权威批量核验，剔除删除/失效来源并记录，全失效则受控失败；校验后标记 cited；url 恒 null。
- 校验：`ResponseValidationService` 增危险协议/本机路径/无来源 URL 检测；`CitationValidationResult`/`CitationValidationView` 增 fabricatedUrls（向后兼容构造）。
- SSE 同语义：`CitationDto` 扩展 sourceId/citationId/quotedSnippet/cited（保留旧 9 参构造）；`CitationRegistry` 经 UrlSafetyPolicy 清洗 url，cited 与 all 区分。
- 前端：`app.js` 移除唯一 innerHTML（replaceChildren），引用 textContent 渲染，客户端协议校验，仅安全 url 才建锚点。

### 存量数据
- 新入库/重索引向量带 page；历史向量 page 保持 null。新增非破坏性 page metadata 覆盖度核对（标记缺失、记录覆盖范围），回填路径=经入库流水线重索引，不做破坏性变更。

## 测试

### 单元/集成（JUnit5 + Mockito，离线 `mvn -o clean test`）
- 最终回归：`Tests run: 43, Failures: 0, Errors: 0, Skipped: 7`，BUILD SUCCESS。
- 8 个可执行测试类（36 个用例全绿）：
  - `UrlSafetyPolicyTest`（6）：协议白名单、危险协议、内网/环回、本机路径、无法解析 fail-closed。
  - `SnippetExtractorTest`（4）：截取上限、代理对不切断、空白处理。
  - `CitationVerificationServiceTest`（6）：批量核验、文档/Chunk 删除、DEPRECATED、markCited、resolveUrl，断言两 Mapper 各一次无 N+1。
  - `ContextAssemblyServiceTest`（5）：S1/S2 编号、页码/段落映射、片段截取、去重、INVALID/BUDGET 排除。
  - `ResponseValidationServiceTest`（5）：[S99] 非法、危险协议/无来源 http/本机路径虚构 URL、UNSUPPORTED。
  - `CitationRegistryTest`（4）：未知标记清洗、cited 语义、危险 URL 清洗、稳定/请求级标识。
  - `RagOrchestratorTest`（5）：happy/部分剔除/全失效 CITATION_TARGET_DELETED/FABRICATED_URL/非 ANSWER 零生成。
  - `RagControllerMockMvcTest`（1）：NON_NULL 隐藏 url 与缺失 page，溯源字段齐全。
- 2 个真实守卫类（默认跳过 7 项）：`RagRealInfrastructureTest`（D35_REAL_INFRA=true，5）、`RagRealChatModelTest`（D35_REAL_CHATMODEL=true 且 AI_MODE=deepseek，2）。
- 前端 Node 测试（Node v22.23.2，内置 node:test）：`front-end-safety.test.js`，6 项全绿，覆盖真实 `isSafeUrl` 与无 innerHTML/外链 rel 契约。

### 过程中修复的真实缺陷（非降测试）
- `ResponseValidationService` 新增危险协议单冒号形态（javascript:/data:/vbscript:/file:/ftp:）拦截，原正则只认 `://`，会漏 `javascript:alert(1)`。
- 前端 `isSafeUrl` 先剥离端口再判定主机名，修复 `localhost:8080` 绕过；同步覆盖 IPv6 方括号。

## 证据
- 环境探测：`docs/evidence/D35/00-env-probe.txt`。
- 评测数据集：`docs/evidence/D35/citation-evaluation-dataset.json`（14 条，actual/passed/failureReason 未预填）。
- 日志：`logs/D35/01-main-compile.log`、`02-unit-test.log`、`03-frontend-node-test.log`、`04-final-regression.log`。
- 阶段一/二临时产物：`docs/temp_docs/D35`（probe 脚本、清单、符号扫描、effective-pom、依赖树）。

## 遗留问题
1. 真实端到端（真实 pgvector + 真实 DeepSeek）本轮默认跳过，需在对应守卫环境变量打开后执行并回填数据集 actual/passed；Mock 不替代真实端到端。
2. 存量旧向量无 page 元数据且 chunk 行无 page 列，page 保持 null；覆盖率经 `VectorReconciliationService.pageMetadataCoverage()` 非破坏统计，回填需重走入库管线。
3. 当前 url 恒为 null（无授权外链场景）；未来若证据携带可信 URL，须经安全策略加入白名单后才允许返回。
4. 未执行 Git commit/push（用户未授权）。
