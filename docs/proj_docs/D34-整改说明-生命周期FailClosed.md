# D34 整改说明：生命周期 Fail-closed 修复

> 整改类型：D34 验收安全整改（P0 阻断项）
> 整改原则：先扫描取证，再局部修改；不重构 RAG 主流程；所有修复均有对应测试与真实执行证据。
> 路径约定：本文统一使用相对路径，仓库根目录记为 `<仓库根>`（即 `M3-Java`），不出现本机绝对路径。

## 一、原始缺陷

### 1.1 缺陷代码

文件：`D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/rag/assessment/DefaultDocumentLifecyclePort.java`

整改前的异常处理：

```java
catch (RuntimeException ex) {
    log.error("生命周期解析异常，回退 ACTIVE，异常类型: {}",
            ex.getClass().getSimpleName());
    return DocumentLifecycleStatus.ACTIVE;
}
```

### 1.2 Fail-open 的四个入口

整改前，以下四类情况都会默认回退到 `ACTIVE`，属于“故障时默认放行（Fail-open）”：

| 序号 | 触发条件 | 整改前行为 |
| ---- | -------- | ---------- |
| 1 | `documentId` 为空白 | 回退 ACTIVE |
| 2 | 查询无行（`po == null`，文档不存在） | 回退 ACTIVE |
| 3 | 查询抛出 `RuntimeException`（含 `PersistenceException`） | 回退 ACTIVE |
| 4 | 存储状态为空/无法识别（`fromName` 未命中） | 回退 ACTIVE |

### 1.3 风险已有实证，非理论推测

真实模型测试日志中已出现：

```text
生命周期解析异常，回退 ACTIVE，异常类型: PersistenceException
```

### 1.4 影响范围

调用链为：

```text
RagOrchestrator.ask
  → EvidenceAssessmentService.assess
      → 对每个证据调 DocumentLifecyclePort.resolve
          → DefaultDocumentLifecyclePort（经 KnowledgeDocumentMapper.selectOne 查 MySQL）
```

证据是否有效仅由 `DocumentLifecycleStatus.isEffective()` 判定。Fail-open 会让已废弃、不存在或查询失败的文档被当作 ACTIVE 进入 `accepted`，进而可能触发 `GenerationService` 调用大模型，使问题文档影响最终回答。

## 二、修复方案

### 2.1 设计目标

任何无法确认生命周期状态的文档，都不能默认作为有效证据；同时明确区分“业务状态”与“基础设施故障”，不把异常简单转成 INACTIVE 隐藏故障，也不通过吞异常、默认值或空对象绕过问题。

### 2.2 生命周期状态处理规则（6 状态）

`DocumentLifecycleStatus` 由 3 状态扩展为 6 状态：

| 状态 | 含义 | `isEffective()` | 类别 |
| ---- | ---- | --------------- | ---- |
| `DRAFT` | 已登记但未生效 | false | 业务态 |
| `ACTIVE` | 已生效，唯一有效状态 | **true** | 业务态 |
| `DEPRECATED` | 已废弃，被新版替代 | false | 业务态 |
| `UNKNOWN` | 存储状态为空/无法解析 | false | 业务态 |
| `NOT_FOUND` | 库中无该文档行（不存在） | false | 业务态 |
| `LOOKUP_FAILED` | 查询基础设施故障 | false | 基础设施故障 |

处理规则：

1. `fromName(String)` 改为 Fail-closed：`null`/空白 → `UNKNOWN`；忽略大小写匹配；未命中 → `UNKNOWN`，不再回退 ACTIVE。
2. `isEffective()` 仅当 `this == ACTIVE` 为真，其余 5 态均非有效。
3. `DefaultDocumentLifecyclePort` 分流：
   - 空白 `documentId` → `UNKNOWN`（warn）；
   - `po == null` → `NOT_FOUND`（info）；
   - 存储状态经 `fromName` 解析，得到 `UNKNOWN` 时 warn；
   - `catch RuntimeException` → `LOOKUP_FAILED`（error）。
4. 异常日志仅记录 `ex.getClass().getSimpleName()` 异常类型，不回显连接串、凭据或堆栈，避免敏感信息泄露。

### 2.3 评估服务受控拒答

`EvidenceAssessmentService` 生命周期过滤段新增 `rejectedStatuses` 跟踪被拒状态。当 `accepted` 为空时：

- 若被拒状态包含 `LOOKUP_FAILED` → 决策 `REFUSE`，错误码 `LIFECYCLE_LOOKUP_FAILED`，提示“生命周期查询失败，无法确认证据有效性，已停止生成，未调用大模型。”；
- 否则维持原 `NO_EVIDENCE`。

由此，基础设施故障与业务上无有效证据明确区分，不静默放行、不隐藏故障。

### 2.4 新增错误码

`RagErrorCode` 末尾追加 `LIFECYCLE_LOOKUP_FAILED`（追加在最后，不改变既有错误码顺序），用于标识生命周期查询失败导致的受控拒答。

## 三、修改文件清单

### 3.1 主代码

| 文件（相对路径） | 修改内容 |
| ---------------- | -------- |
| `D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/document/model/DocumentLifecycleStatus.java` | 重写为 6 状态，Fail-closed `fromName`，`isEffective` 仅 ACTIVE |
| `D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/rag/assessment/DocumentLifecyclePort.java` | 接口 javadoc 同步 Fail-closed 契约 |
| `D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/rag/model/RagErrorCode.java` | 末尾新增 `LIFECYCLE_LOOKUP_FAILED` |
| `D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/rag/assessment/DefaultDocumentLifecyclePort.java` | 重写为 Fail-closed（NOT_FOUND/UNKNOWN/LOOKUP_FAILED 分流，异常仅记类型） |
| `D34/D34_ABONEARTAWE/src/main/java/com/zl/d34/rag/assessment/EvidenceAssessmentService.java` | `rejectedStatuses` 跟踪 + accepted 空分支分流 |

### 3.2 测试代码与夹具

| 文件（相对路径） | 修改内容 |
| ---------------- | -------- |
| `D34/D34_ABONEARTAWE/src/test/java/com/zl/d34/rag/real/RagRealTestSupport.java` | 注入 `KnowledgeDocumentMapper`，新增 `ensureLifecycleRows/cleanupLifecycleRows` 并接入夹具生命周期 |
| `D34/D34_ABONEARTAWE/src/test/java/com/zl/d34/rag/assessment/DefaultDocumentLifecyclePortTest.java` | 新增端口级测试（9 个用例） |
| `D34/D34_ABONEARTAWE/src/test/java/com/zl/d34/rag/assessment/LifecycleFailClosedAssessmentTest.java` | 新增评估级测试（4 个用例） |
| `D34/D34_ABONEARTAWE/src/test/java/com/zl/d34/rag/orchestrator/RagOrchestratorTest.java` | 新增生命周期查询失败零模型调用用例 |

## 四、测试证据

### 4.1 T01–T09 场景与测试映射

| 场景 | 模拟情况 | 预期 | 实际结果 | 覆盖测试 |
| ---- | -------- | ---- | -------- | -------- |
| T01 | 状态 ACTIVE | 允许有效证据 | 通过，返回 ACTIVE 且 `isEffective=true` | `DefaultDocumentLifecyclePortTest.t01_activeDocumentResolvesActive` |
| T02 | 状态 INACTIVE（本工程等价 DRAFT） | 不允许 | 通过，DRAFT 非有效 | `t02_draftDocumentIsNotEffective` |
| T03 | 状态 DELETED（等价 DEPRECATED） | 不允许 | 通过，DEPRECATED 非有效 | `t03_deprecatedDocumentIsNotEffective` |
| T04 | 文档不存在 | 明确 NOT_FOUND，不默认 ACTIVE | 通过，返回 NOT_FOUND | `t04_missingDocumentResolvesNotFound` |
| T05 | 查询抛 `PersistenceException` | 不返回 ACTIVE | 通过，返回 LOOKUP_FAILED | `t05_persistenceExceptionResolvesLookupFailed` |
| T06 | 状态解析失败 | 不返回 ACTIVE | 通过，返回 UNKNOWN | `t06_unrecognizedStoredValueResolvesUnknown` |
| T07 | 状态 UNKNOWN | 不允许有效证据 | 通过，REFUSE/NO_EVIDENCE，拒绝生成 | `LifecycleFailClosedAssessmentTest.t07_unknownIsRejectedAndRefuses` |
| T08 | 多证据一条查询失败 | 失败条被拒、其余有效可继续 | 通过，S1 拒绝、S2 接受并 ANSWER | `t08_oneLookupFailedRejectedOtherActiveContinues` |
| T09 | 查询恢复正常 | ACTIVE 恢复检索回答 | 通过，先 LOOKUP_FAILED 后 ACTIVE | `DefaultDocumentLifecyclePortTest.t09_recoversAfterFailure` |

### 4.2 关键安全断言

1. 异常后失效/未知文档不进入最终上下文：评估级用例验证非 ACTIVE 证据进入 `rejectedEvidenceIds`，不进入 `acceptedEvidenceIds`。
2. 异常后不调用 ChatModel：`RagOrchestratorTest.lifecycleLookupFailureShouldRefuseWithoutModelCall` 通过 `verify(generationService, never()).generate(...)` 验证零模型调用。
3. 正常 ACTIVE 不受影响：真实基础设施测试接地路径仍为 ANSWER，真实 ChatModel 接地回答 SUCCESS。
4. 异常日志可定位：LOOKUP_FAILED 记录 error 与异常类型，NOT_FOUND 记录 info，UNKNOWN 记录 warn。

### 4.3 真实执行结果

- 全量离线回归：`Tests run: 68, Failures: 0, Errors: 0, Skipped: 11`，BUILD SUCCESS。
- 真实基础设施（真实 pgvector + 真实 MySQL，零模型调用）：5/5 通过。
- 真实 ChatModel（真实 DeepSeek，严格 2 次真实调用）：3/3 通过。

详细命令与逐项结果见《D34-整改回归测试报告.md》。

## 五、兼容性说明

1. 新增状态与错误码均为追加/扩展，未删除既有状态，未改变既有错误码顺序。
2. Fail-closed 后，真实测试夹具原本无 MySQL 行的路径会被 NOT_FOUND 拒绝；已通过 `RagRealTestSupport` 补 ACTIVE 生命周期行解决，且仅作用于测试夹具，不污染生产。
3. 未改变 Embedding、VectorStore、Top-K、384 维等既有基线，未重写 `RagOrchestrator` 等主流程类。
