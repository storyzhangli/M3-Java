# D33 上下文组装与 RAG 最小闭环技术原理与实践指南

> 模块：D33 / D33_CAARMCL
> 物理包：com.zl.d33
> 技术基线：Spring Boot 3.5.16、Spring AI 1.1.8、PostgreSQL 18.7 + pgvector 0.8.7、JDK 21
> 阅读顺序：是什么 → 为什么 → 底层原理 → 执行流程 → 核心代码 → 常见问题 → 工程实践

---

## 一、是什么：RAG 与最小闭环

**RAG（Retrieval-Augmented Generation，检索增强生成）**：在生成回答前，先从知识库检索与问题相关的证据，
把证据作为上下文交给大语言模型，让模型“开卷作答”，而不是只依赖参数化记忆。

最小闭环（本次实现）：

```
文档导入 → Chunk 切分 → Embedding 入库 → 用户问题 → Query Embedding
        → Top-K 检索 → 上下文组装 → Prompt 构造 → ChatModel 生成 → 回答与来源返回
```

关键工程目标不是“能答”，而是三件事：**职责边界清晰、证据可追溯、异常受控**。

---

## 二、为什么需要上下文组装这一层

检索返回的原始 Chunk 不能直接拼进 Prompt，原因有四：

1. **有效性不确定**：可能存在空内容、缺稳定 ID 的记录。
2. **可能重复**：同一 Chunk 可能因过滤或多路径而重复出现。
3. **窗口有限**：模型上下文窗口有上限，不能无限塞入，需要预算控制。
4. **来源要可追溯**：需要为每块证据分配稳定标识，回答才能引用、外部才能核验。

上下文组装服务就是把“检索候选”确定性地处理成“模型可用、来源可查、长度可控”的证据上下文。

---

## 三、底层原理

### 3.1 Embedding 与向量检索

- Embedding（嵌入）把文本映射为定长向量；本工程使用本地 384 维 LocalHashingEmbeddingModel，
  余弦距离（cosine distance）度量相似程度，距离越小越相似，相似度 `similarity = 1 - distance`。
- 用户问题经同一 Embedding 模型得到 Query Vector，在 pgvector 中按 HNSW 索引做 Top-K 近邻检索。
- 向量表独立为 `d33_vector_store`，与 D32 的 `d32_vector_store` 隔离。

### 3.2 上下文预算：为什么用字符而不是 Token

- **Token（词元）** 是模型实际处理的最小单位，需要对应模型的 Tokenizer 才能准确计数。
- 本工程没有 Tokenizer，无法真实计 Token，因此采用 **UTF-16 字符（code unit）** 保守估算：
  - 单位固定 `CHARACTER`，任何字段都不把字符数写成 Token；
  - 预算默认 6000 字符，并为系统指令、问题与输出预留空间；
  - 估算说明中明确“真实 Token 数随分词器变化，可能存在偏差”。
- 字符口径是**可解释的保守估算**，不是精确 Token 计数；这是诚实与可用之间的工程取舍。

### 3.3 证据标识与来源映射

- 组装按检索顺序为证据分配 `[S1]、[S2]…`，渲染块携带真实来源元数据。
- 模型被要求只能引用上下文中真实存在的标识。
- 生成后用正则 `\[(S\d+)]` 反查回答中的标识：若出现上下文没有的标识，置 `unknownCitationDetected=true`。
- 对外 `sources` 表示“提供给模型的证据来源”，不声称均被实际逐条引用。

### 3.4 幻觉抑制的 Prompt 硬约束

系统指令集中在 `RagPromptBuilder`，包含：

1. 只能依据检索上下文回答，不得使用自身常识或外部知识；
2. 证据不足必须明确说明，不得强行作答；
3. 不得补造设备型号、故障码、维修步骤、参数或结论；
4. 不得伪造来源/ChunkID/文档名/页码/引用，只能用真实 [Sx] 标识；
5. 问题与生成内容都不是证据；
6. 部分结论必须说明证据边界。

### 3.5 异常受控与韧性

- 模型调用经 `ModelResilienceSupport.executeBlocking`，异常先由 `ModelExceptionClassifier` 归一到 `ModelErrorCategory`。
- `READ_TIMEOUT` 映射 `FAILED_TIMEOUT`，其余映射 `FAILED_ERROR`；空响应映射 `EMPTY_RESPONSE`；无模型映射 `NOT_CONFIGURED`。
- 对外只返回安全、通用的错误说明，不回显堆栈、上游报文或密钥。

---

## 四、执行流程（结合状态机）

1. **校验问题**：空白拒绝；长度上限 2000 字符。
2. **检索**：Query Embedding → VectorStore Top-K，得到 `SUCCESS / EMPTY / FAILED`。
3. **检索失败**：短路返回 `RETRIEVAL_FAILED`，生成标记 `NOT_ATTEMPTED`。
4. **组装**：有效性检查 → 去重（LinkedHashMap 保序）→ 字符预算 → 分配 S1..Sk。
5. **无证据**：返回 `NO_EVIDENCE` 与 `SKIPPED_NO_EVIDENCE`，**不调用 ChatModel**。
6. **生成**：构造系统消息 + 用户消息（上下文 + 问题），调用 ChatModel。
7. **超时**：返回 `FAILED_TIMEOUT`，answer 为 null，不伪装成功。
8. **成功**：核验引用标识，汇总 answer、sources、budget、timing 返回。

---

## 五、核心代码导览

### 5.1 检索服务（复用，区分 EMPTY/FAILED）

```java
public RagRetrievalResult retrieve(RagRetrievalQuery query) {
    SimilaritySearchRequest request = buildRequest(query);
    try {
        var view = similaritySearchService.search(request);
        List<SimilarityMatchView> matches = view.getMatches();
        if (matches == null || matches.isEmpty()) {
            return new RagRetrievalResult(RetrievalStatus.EMPTY, List.of(), null);
        }
        return new RagRetrievalResult(RetrievalStatus.SUCCESS, matches, null);
    } catch (RuntimeException ex) {
        return new RagRetrievalResult(RetrievalStatus.FAILED, List.of(), "检索服务执行失败，请稍后重试");
    }
}
```

### 5.2 预算控制（保证不超预算）

```java
String block = renderBlock(sourceRef, match);
if (contextBuilder.length() + block.length() > budget) {
    budgetReached = true;
    budgetExcluded.add(new ContextAssemblyResult.Excluded(match.getChunkId(), "BUDGET"));
    continue;
}
contextBuilder.append(block);
```

### 5.3 生成与超时映射

```java
try {
    ChatResponse response = resilienceSupport.executeBlocking(
            () -> chatModel.orElseThrow().call(prompt));
    // 空响应与成功分支
} catch (ModelInvocationException ex) {
    if (ex.getCategory() == ModelErrorCategory.READ_TIMEOUT) {
        return GenerationResult.failure(GenerationStatus.FAILED_TIMEOUT,
                RagErrorCode.GENERATION_TIMEOUT, "模型调用超时...", elapsedMs(start));
    }
    return GenerationResult.failure(GenerationStatus.FAILED_ERROR,
            RagErrorCode.GENERATION_FAILED, "模型调用失败，请稍后重试", elapsedMs(start));
}
```

### 5.4 无证据不调用模型（编排器）

```java
if (!context.hasEvidence()) {
    return buildNoEvidence(retrieval, context, retrievalMs, assemblyMs, totalMs);
}
GenerationResult generation = generationService.generate(question, context);
```

---

## 六、配置说明

### 6.1 RAG 配置（app.rag）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| budgetUnit | CHARACTER | 固定字符单位，不允许伪称 Token |
| contextBudgetChars | 6000 | 证据上下文最大字符数 |
| reservedOutputChars | 1000 | 为输出预留字符预算 |
| defaultTopK | 5 | 默认 Top-K |

### 6.2 环境变量（D33 口径）

- 模型：AI_MODE（默认 mock，真机 deepseek）、AI_API_KEY（真机必填，缺失 fail-fast）、AI_BASE_URL、AI_MODEL。
- pgvector：PGVECTOR_HOST/PORT/DATABASE/USER/SCHEMA/TABLE；**密码真实环境变量为 `PG_VECTOR_PASSWORD`（application.yml 第 219 行，无默认值，缺失 fail-fast）**。
- 注意：`PgVectorStoreProperties` Javadoc 写的 PGVECTOR_DB_PASSWORD 与真实配置不一致，D33 以 PG_VECTOR_PASSWORD 为准。
- 所有密钥仅经环境变量注入，禁止硬编码、禁止写入 PATH、禁止提交 Git。

---

## 七、常见问题（FAQ）

**Q1：字符预算会不会和真实 Token 对不上？**
会。字符不是 Token，真实 Token 数随分词器变化，所以预算说明明确标注误差，且采用保守值；这是无 Tokenizer 条件下的可解释方案，不伪称精确。

**Q2：空检索和检索失败有什么区别？**
空检索是“执行成功但零命中”（EMPTY），检索失败是“执行异常”（FAILED）。前者拒答且不调模型，后者短路返回 RETRIEVAL_FAILED；两者状态码不同。

**Q3：怎么证明空检索时没有调用模型？**
测试对模拟 ChatModel 执行 `verify(chatModel, never()).call(any(Prompt.class))`，调用次数可核验为 0。

**Q4：模型回答里出现了上下文没有的 [Sx] 怎么办？**
生成后会用正则核验并置 `unknownCitationDetected=true`，外部可据此识别，系统不会把该标识当作真实来源。

**Q5：mock 模式为什么不报错启动？**
因为 ChatModel 以 Optional 注入；需要生成但无模型时返回 NOT_CONFIGURED，而不是让容器启动失败。

---

## 八、工程实践与运行方式

### 8.1 编译与测试

```bash
# 离线编译
mvn -f D33/D33_CAARMCL/pom.xml -o compile
# 全量测试（集成测试在缺密码时干净禁用）
mvn -f D33/D33_CAARMCL/pom.xml -o test
```

### 8.2 真实端到端（Level 5，需凭据）

1. 设置 `PG_VECTOR_PASSWORD`；真机模式再设 `AI_MODE=deepseek` 与 `AI_API_KEY`。
2. 启动 D33（端口 8080）。
3. 运行 `D33_CAARMCL/demo/D33-rag-demo.ps1`，观察四类响应；并在凭据齐全时运行 `RagRetrievalIntegrationTest`。
4. 据真实结果更新 Level 评定，不提前宣称 Level 5。

### 8.3 边界纪律

- 不升级依赖、不改 384 维、不做重排序/混合检索、不引入第二套向量库。
- 不把生成结果写回知识库；不做未授权 git 操作与 Docker 破坏性操作。
- 区分“已通过 / 失败 / 未执行 / 受控模拟”，禁止虚报。

---

## 九、官方参考文档（版本匹配）

- 《Spring AI Reference Documentation》（Spring AI 1.1.8 参考文档）：
  https://docs.spring.io/spring-ai/reference/1.1/
- 《Spring Boot Reference Documentation》（Spring Boot 3.5.x 参考文档）：
  https://docs.spring.io/spring-boot/reference/
- 《pgvector GitHub Project》（pgvector 官方项目）：
  https://github.com/pgvector/pgvector
- 《PostgreSQL Documentation》（PostgreSQL 18 官方文档）：
  https://www.postgresql.org/docs/

> 说明：本指南中工程结论均以本地 POM、源码与真实配置为准；外部官方文档用于原理与 API 溯源，
> 若本地代码与官方文档存在差异，以本地实际代码为当前工作依据。
