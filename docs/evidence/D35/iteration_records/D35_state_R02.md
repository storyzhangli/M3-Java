# D35 迭代状态

## 需求编号
D35-R02：引用溯源与返回契约（P1 整改切片：引用归属校验与 Claim-Citation 关联验证）

基线提交 `d81f141`；父提交 `1c975db`。本切片只处理验收两项 P1，不改检索算法、Embedding 维度、D34 生命周期策略与现有端点。

## 阶段一：缺陷证据与根因

- P1-01：`CitationVerificationService.loadChunks` 返回 `Set<String>`，只判 Chunk 存在；`classify` 缺少归属环节，DOC-B 正文可配 DOC-A 标题/版本/页码返回。权威归属列为 `DocumentChunkIndexPO.documentId`。
- P1-02：`ResponseValidationService.checkFactualSupport` 使用全局 `supportedFacts` 并集，断言与其标注引用无绑定关系；“0.8 [S1]”在 0.8 仅存在于 S2 时仍通过。
- 扫描确认 `EvidenceItem.documentId` 来源为向量 metadata，与 Chunk 行归属为两处独立数据，必须交叉校验。

## 阶段二：设计与实现

### P1-01 归属校验
- `loadChunks` → `loadChunkOwners`：一次 `IN` 查询得 `chunkId -> documentId`；同 chunkId 多归属映射 `null`（歧义）。
- `classify` 顺序：文档缺失 → `CITATION_TARGET_DELETED`；chunkId 缺失/查不到 → `CITATION_TARGET_DELETED`；归属 null/空白 → `CITATION_OWNERSHIP_MISMATCH`；归属不等 → `CITATION_OWNERSHIP_MISMATCH`；一致后才判 `isEffective`。
- `RagErrorCode` 追加 `CITATION_OWNERSHIP_MISMATCH`（20 → 21）。
- `selectRemovalCode` 优先级：删除 > 归属 > 生命周期。

### P1-02 Claim 关联
- 证据侧 `factsByRef`（sourceRef → subject|parameter|value 集合），移除全局并集。
- 回答侧：按 `。！？；`/换行切句；句内按 `MARKER_RUN_PATTERN` 切标记组；文本归属其后标记组；句末残余沿用最近组；整句无标记时仅继承相邻纯引用句；`[S1][S2]` 同组任一支持即通过；无支持写入 `unsupportedClaims` 与 `claimCitationMismatches`。
- `CitationValidationResult` 6 组件（保留 5/4 参构造器）；`CitationValidationView` 加字段；编排器回填。

## 测试

### 新增/改写用例
- `CitationVerificationServiceTest`（11）：归属一致且两 Mapper 各一次、Chunk 不存在、归属不一致、多来源仅一错、归属缺失、同 chunkId 多归属、批量映射、文档不存在、DEPRECATED、markCited、resolveUrl。
- `ResponseValidationServiceTest`（14）：自证支持、他处存在但所引不支持、冲突值引错源、同句两值互换、多引用至少一个支持、部分 Claim 错配、引用不存在、无标记、句末独立引用行继承、既有 URL/本机路径/UNSUPPORTED/空回答回归。
- `RagOrchestratorTest`（7）：新增归属全失效受控失败、Claim 错配校验失败并回填明细。
- `CitationDatasetExecutionTest`（新增）：受 `D35_DATASET_RUN=true` 守卫，生产组件真实执行评测集并落盘，未知断言键判失败。

### 真实执行
- 首轮：`Tests run: 59, Failures: 0, Errors: 0, Skipped: 7`。
- 最终：`Tests run: 60, Failures: 0, Errors: 0, Skipped: 8`，BUILD SUCCESS，EXIT=0。
- 跳过 8 = 真实基础设施 5 + 真实 ChatModel 2 + 评测集执行器 1（常规回归未开守卫）。

## 评测集

- `docs/evidence/D35/citation-evaluation-dataset.json` 升至 v1.1：14 → 23 条（新增 015–023，覆盖归属不一致、归属缺失、他处存在、同句互换、多引用、无标记、部分错误、归属与 Claim 并存）。
- 执行结果：`total=23, executed=22, passed=22, failed=0, notExecuted=1`（D35-014 SSE/前端一致性标记 `not_executable_here`，不计通过）。
- 执行模式 `CONTROLLED_FIXTURE_VALIDATOR_LEVEL`，limitations 已写入结果文件。

## 过程中修复的真实问题（非降测试）

1. D35-010 期望键语义混淆（上限 200 vs 实际片段 13）：改用 `quotedSnippetLimit` 表达上限，保留 `maxQuotedSnippetLength` 观测值。
2. 相邻引用继承仅看单侧：改为跨空白纯引用句向前/向后查找。
3. D35-023 夹具自相矛盾（S1/S2 共用 chunk-B1）：改为 S1=chunk-B2、S2=chunk-B1。

## 文档与版本口径

- D35-01：补 21 原因码与剔除优先级、P1-01/P1-02 小节、9 测试类与 60/0/0/8、23 条评测集与 22/22/1、九项指标、评测集复现命令、6 条风险。
- D35-02：补“引用合法≠引用正确、事实存在≠所引支持”原则、三层证据可信性、归属校验代码、Claim 关联代码、sourceId 约定、失败模式 4 条、整改索引。
- D35 知识笔记：明确概念层 Source≠Document，本项目 `sourceId=documentId` 为数据模型约定。
- 六份费曼文档（D30–D35）版本基线统一为 PostgreSQL 18.7、pgvector 0.8.7；`docs/proj_docs` 已无 `18.6` / `pgvector 0.8.x` 残留。

## 证据

- `docs/evidence/D35/remediation/D35-整改说明.md`
- `docs/evidence/D35/remediation/D35-整改回归测试报告.md`
- `docs/evidence/D35/remediation/D35-评测集执行结果.json`
- `logs/D35/05-remediation-compile.log`、`05a-remediation-test-iter1.log`、`06-remediation-dataset.log`、`07-remediation-final-regression.log`

## 遗留问题

1. 真实端到端（真实 pgvector + 真实 DeepSeek + 真实 MySQL）本轮未执行；需用户提供真实 PG 口令并按最小调用守卫补跑。
2. 评测集为校验器级受控夹具，不等价端到端。
3. Claim-Citation 为确定性规则，复杂语义可能漏判，策略为保守失败。
4. 历史 surefire 报告目录仍被 Git 跟踪（含环境变量路径），建议后续单独治理。
5. 未执行 push。
