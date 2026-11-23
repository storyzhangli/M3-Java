# D40 知识笔记：双写一致性风险与 Outbox / 重试 / 补偿的适用边界

>
> 技术基线：JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / Spring Cloud 2025.0.0 / MySQL 8.0.33（ai_db）/ PostgreSQL 18.7 + pgvector 0.8.7（spring_ai）/ Servlet Tomcat + Spring MVC
>
>
> 标注体系：`[项目已实现]`=已实读 D31 源码确认 / `[业界通用]`=标准模式非本项目现状 / `[待验证]`=推断未实证。故障注入全部为**设计方案，未真实执行**，不伪造任何测试结果。
>
>
> 核心结论先行：**MySQL 本地事务覆盖不到 PG，双写窗口是问题起点而非实现疏忽；本项目以"状态机 + 重试 + 对账"闭环解决，Outbox/MQ 的引入判据是消费者数量，不是数据量。**

---

## 0. 事实基线（引用任务书 §1.2 实读锚点）

| 锚点           | 真实位置                                           | 已核实事实                                                   |
| -------------- | -------------------------------------------------- | ------------------------------------------------------------ |
| 阶段顺序       | `com.zl.d31.document.vectorindex.model.IndexStage` | `SPLIT → EMBED_WRITE → SWITCH → CLEANUP`，状态表记录最后完成阶段，恢复时从其后续跑 `[项目已实现]` |
| 向量索引状态机 | `...vectorindex.model.VectorIndexStatus`           | `PENDING → INDEXING → COMPLETED`；异常收敛 `PARTIAL`/`FAILED`；旧版本 `SUPERSEDED` `[项目已实现]` |
| 文档解析状态   | `...document.model.DocumentStatus`                 | `PENDING / PROCESSING / COMPLETED / FAILED`，与上一套**不得混用** |
| 生命周期状态   | `DocumentLifecycleStatus`                          | `DRAFT / ACTIVE / DEPRECATED`（`ACTIVE` 仅属于本枚举）       |
| 关联行状态     | `ChunkRowStatus`                                   | `INDEXED / FAILED`                                           |
| 写入顺序       | `DocumentVectorIndexService#indexChunks`           | 先 `vectorStore.add()` 写 PG，再 `chunkMapper.upsertOnNaturalKey()` 写 MySQL `[项目已实现]` |
| 并发首建       | `DuplicateKeyException` 捕获 + `uk_doc_version`    | 一方胜出、失败方读胜出行继续，不抛错 `[项目已实现]`          |
| 对账四类       | `VectorReconciliationService#reconcile`            | 孤儿向量 / 缺失向量 / 数量不一致 / 卡住任务；`dryRun` 只报告，非 `dryRun` 才补偿 `[项目已实现]` |
| 调度开关       | `VectorIndexSchedulingConfiguration`               | `@ConditionalOnProperty(reconcile-enabled=false)` 默认关闭 `[项目已实现]` |

>
> 代码中**不存在** `DELETING` / `DELETED` 向量索引状态；`PARTIAL` 是"部分行失败但任务可恢复"，`FAILED` 是"终态需人工或重建"。

---

## 1. 原理层：双写失败窗口

### 1.1 为什么双写不可原子化 `[业界通用]`

一个 Spring `@Transactional` 只绑定一个 `DataSource`。MySQL（业务库）与 PG（向量库）是独立连接、独立事务管理器，本地事务互不可见；XA/2PC 在两库场景运维成本高、锁持有时间长，实用价值有限。所以"业务数据变更 + 向量索引变更"天然存在崩溃窗口，**任何方案都是在管理这个窗口，不是消灭它**。

### 1.2 三种失败形态 + 一个感知陷阱

| #    | 形态                   | 成因                                          | 检索侧后果                                          | 安全等级                                                     |
| ---- | ---------------------- | --------------------------------------------- | --------------------------------------------------- | ------------------------------------------------------------ |
| 1    | **悬空元数据**         | MySQL 状态行成功 + PG 向量失败                | 文档标了 ACTIVE 但召不回内容——**召回缺失**          | 业务降级（安全）：用户得到"无证据→拒答/澄清"，不会拿到错误知识 |
| 2    | **幽灵向量**           | PG 向量成功 + MySQL 元数据失败/删除半途       | 已删除/已撤权文档**仍被 `<=>` 召回进上下文**        | **安全事件**：越权可见，见 Q6                                |
| 3    | **版本切换非原子混存** | 新旧版本向量同时在 PG，活动指针更新前崩溃     | 新旧内容混召→答案自相矛盾（参数取旧版、步骤取新版） | 正确性风险                                                   |
| 4    | **超时不确定**         | 客户端发出写/删请求后网络超时，库端可能已执行 | 客户端无法判定成败：直接重试可能重复，放弃可能残留  | 元问题：必须先收敛感知，再谈修复                             |

形态 4 的本质是三个状态可能不一致：**事务真实结果（DB 端）≠ 库端最终状态（含后续触发器/复制）≠ 客户端感知**。超时后唯一正确动作是"读回验证"（按稳定自然键 SELECT 确认），而不是凭猜重试——这正好由 D31 的稳定 ChunkId + `upsertOnNaturalKey` 支撑：读回失败就重放幂等写，重放不产生第二行。

PostgreSQL 的并发与事务语义（READ COMMITTED、MVCC 快照）决定了单库内不存在"读到半行"，但跨库没有任何隔离级别能帮你：Transaction isolation | 事务隔离级别：[https://www.postgresql.org/docs/18/transaction-iso.html](https://www.postgresql.org/docs/18/transaction-iso.html)

### 1.3 暂时不一致 vs 永久漂移；最终一致 ≠ 随便等

- **暂时不一致**：在既定收敛时限（如 ≤ 一个对账周期）内被补偿消除，属于设计内状态；
- **永久漂移**：无补偿器、补偿反复失败、或对账默认关闭（本项目 `reconcile-enabled=false` `[项目已实现]`）时，漂移无人认领——"最终一致"退化为"看运气一致"。
- 结论句：宣称"最终一致"必须先给出**收敛时限、补偿责任人、残留上限**三要素，缺一项就是没有一致性目标。

---

## 2. 流程图一：更新正序与失败分支（对齐真实 IndexStage）

```
graph TD
    T0["更新任务 PENDING"] --> S1["SPLIT 切分并写 split_version 暂存"]
    S1 --> S2["EMBED_WRITE: 先 vectorStore.add 写 PG 新版本向量, 再 upsertOnNaturalKey 写 MySQL 关联行"]
    S2 --> S3["SWITCH: MySQL 活动指针 doc_version 指向新版本, 旧版本行标记 SUPERSEDED"]
    S3 --> S4["CLEANUP: 按 document_id+旧version 条件删除 PG 旧向量"]
    S4 --> OK["COMPLETED"]
    S1 -->|"崩溃于 SPLIT"| R1["残留: 无新版本数据, 旧版本仍活动"]
    R1 --> F1["恢复: 任务重跑从 SPLIT 幂等续做, 零风险"]
    S2 -->|"崩溃于 EMBED_WRITE"| R2["残留: PG 有未激活新版本向量, MySQL 指针未动"]
    R2 --> F2["恢复: 重跑走稳定ID Upsert 不重复; 放弃则按版本清理, 成本≈0"]
    S3 -->|"崩溃于 SWITCH 前"| R3["残留: 双端就绪但指针仍指旧版, 检索只见旧版"]
    R3 --> F3["恢复: 重放 SWITCH 单行 UPDATE 即收敛(可回滚窗口)"]
    S4 -->|"崩溃于 CLEANUP"| R4["残留: SUPERSEDED 旧版本向量滞留 PG = 幽灵候选"]
    R4 --> F4["恢复: 对账识别旧版本向量, 确认状态后按版本删除"]
```

更新顺序的设计意图：**先写新（EMBED_WRITE）、后切旧（SWITCH）、最后清理（CLEANUP）**——任意崩溃点旧版本都仍然完整可服务，系统始终"可用优先"。SWITCH 是 MySQL 单行指针更新，这一步是新旧世界唯一的分界线，因此检索侧必须以**单一活动指针字段**判定版本，不允许"取 max(version)"之类双口径（两口径不一致即混存窗口）。

---

## 3. 流程图二：删除正序与失败分支

```
graph TD
    D0["删除请求"] --> X1["STEP1: MySQL 标记 DEPRECATED + 活动指针摘除(逻辑不可见)"]
    X1 --> X2["STEP2: PG 按 document_id+version 条件删除向量行"]
    X2 --> X3["STEP3: MySQL 清理元数据与状态行(物理收尾)"]
    X3 --> DONE["删除完成"]
    X1 -->|"崩溃于 STEP1 后"| P1["残留: 元数据已不可见, 向量仍在 PG"]
    P1 --> C1["补偿: 检索被活动指针挡住不可召回; 对账补删向量, 成本≈0"]
    X2 -->|"崩溃于 STEP2 半途"| P2["残留: 部分向量已删, 元数据已不可见"]
    P2 --> C2["补偿: 按版本条件重放删除(DELETE 天然幂等)"]
    X3 -->|"崩溃于 STEP3"| P3["残留: 向量已清空, 元数据滞留但已标 DEPRECATED"]
    P3 --> C3["补偿: 重放清理, 无任何可召回面"]
```

**删除先删元数据（摘可见性），后删向量**——与更新顺序相反，理由对照：

|              | 更新                             | 删除                                       |
| ------------ | -------------------------------- | ------------------------------------------ |
| 顺序         | 先写新 → 切指针 → 清旧           | 先摘可见性 → 删向量 → 清元数据             |
| 保证         | 任意崩溃点**服务不断**（可回滚） | 任意崩溃点**不可召回已删内容**（不可泄露） |
| 崩溃残留性质 | 未激活新向量（无害垃圾）         | 未删净旧向量（被指针屏蔽的无害垃圾）       |

成本不对称论证（详见 Q1）：重建向量要重新 Embedding（真金白银 + 分钟级耗时），删除孤儿向量只是一条带版本条件的 DELETE——**宁可残留垃圾向量，绝不残留可召回的已删内容**。

---

## 4. 方案层：Outbox `[业界通用，本项目未实现]`

### 4.1 铁律

事件表与业务表**必须同库同事务**提交。如果事件写到另一个库/队列，Outbox 自身就退化成第二次双写，问题原样复发。

### 4.2 表结构（落 MySQL，与业务表同库）

```
CREATE TABLE outbox_event (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  event_id          CHAR(36)     NOT NULL COMMENT 'UUID,消费端幂等键',
  aggregate_type    VARCHAR(64)  NOT NULL COMMENT '如 KNOWLEDGE_DOCUMENT',
  aggregate_id      VARCHAR(64)  NOT NULL COMMENT 'document_id',
  aggregate_version BIGINT       NOT NULL COMMENT '聚合单调版本,防乱序防重放',
  event_type        VARCHAR(32)  NOT NULL COMMENT 'DOC_INDEXED/DOC_UPDATED/DOC_DELETED',
  payload           JSON         NOT NULL,
  status            TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=PUBLISHED 2=FAILED',
  retry_count       INT          NOT NULL DEFAULT 0,
  next_retry_at     DATETIME(3)  NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at      DATETIME(3)  NULL,
  UNIQUE KEY uk_event_id (event_id),
  UNIQUE KEY uk_agg_ver  (aggregate_type, aggregate_id, aggregate_version),
  KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`uk_agg_ver` 是双重防线：同一聚合同一版本只能有一个事件（发布幂等），且版本号唯一约束天然拒绝旧版本事件补写。唯一约束语义参考：Unique Index Constraints | MySQL 唯一索引约束：[https://dev.mysql.com/doc/refman/8.0/en/create-table-secondary-indexes.html](https://dev.mysql.com/doc/refman/8.0/en/create-table-secondary-indexes.html)

### 4.3 发布语义与投递方式

发布流程：业务事务内 `INSERT INTO outbox_event ...` → 提交；中继进程取 PENDING → 投递 → `UPDATE status=1`。投递语义上限：**at-least-once；exactly-once 在分布式下不可达，工程解只有 at-least-once + 消费幂等 = effectively-once（效果上一次性）**。

| 维度       | 轮询中继                     | CDC（逻辑复制 / Debezium）             |
| ---------- | ---------------------------- | -------------------------------------- |
| 延迟       | 秒~分钟（轮询周期）          | 亚秒                                   |
| 实现复杂度 | 低（一条 SELECT + UPDATE）   | 中（连接器 + schema 演进）             |
| 运维负担   | 空转查询、发布位点在自己表里 | 复制槽/位点管理、权限、wal 膨胀风险    |
| 选择条件   | 吞吐低、RTO 分钟级、团队小   | 高频变更、多下游、RTO 秒级、有专职运维 |

PostgreSQL 逻辑复制：Logical Replication | PostgreSQL 逻辑复制：[https://www.postgresql.org/docs/18/logical-replication.html](https://www.postgresql.org/docs/18/logical-replication.html)
Outbox 模式出处：Transactional Outbox Pattern | 事务性发件箱模式：[https://microservices.io/patterns/data/transactional-outbox.html](https://microservices.io/patterns/data/transactional-outbox.html)

### 4.4 本项目定位

本项目单应用内 MySQL→PG 投影无跨服务事件消费者，**不引入 Outbox** `[项目已实现-决策]`；若未来设备域/审计域/缓存失效三方要消费文档变更事件，Outbox 是第一步，MQ 是第二步，顺序不可反。

---

## 5. 幂等层：三种手段的能力边界

| 手段       | 实现                                                     | **能防**                                          | **不能防**                                      | 本项目对应                                                 |
| ---------- | -------------------------------------------------------- | ------------------------------------------------- | ----------------------------------------------- | ---------------------------------------------------------- |
| 唯一键     | `uk_doc_version` + `upsertOnNaturalKey`                  | 重复插入、并发首建双写（一方胜出）                | **误删**（DELETE 不撞唯一键）、旧事件覆盖新版本 | `ChunkMapper.upsertOnNaturalKey` `[项目已实现]`            |
| 状态机 CAS | `UPDATE ... SET status=下一态 WHERE status=当前态`       | 重放二次推进、非法跳态                            | 不带版本条件的跨版本误操作                      | `IndexStage` 记录最后完成阶段，恢复从后续跑 `[项目已实现]` |
| 版本号     | `WHERE version = ?` / `aggregate_version > last_applied` | 乱序旧事件、已删/已替代内容被复活、**误删新版本** | 单独用不防并发首建插入（需配唯一键）            | 自然键含 `doc_version` `[项目已实现]`                      |

**唯一键防不了误删——完整论证**：稳定 ChunkId 由"内容 + 位置"派生（`StableChunkIdGenerator`），文档 v3 某段与 v2 完全相同且位置相同 → 同一 chunkId。携带 chunkId 列表的删除事件重放时，`DELETE WHERE chunk_id IN (...)` 唯一键毫无拦截能力，直接删掉 v3 活动行。正确删除谓词是三元 CAS：

```
DELETE FROM vector_store
WHERE document_id = ? AND version = ?        -- 版本锁定
  AND ? >= superseded_boundary_version;      -- 服务端前置判定:仅删 SUPERSEDED 版本
-- 执行前先校验任务态:INDEXING/PENDING/PARTIAL 期间禁止清理(见 Q5)
```

组合原则：**插入靠唯一键，推进靠状态机 CAS，删除/更新靠版本条件**，三者各守一段，缺一漏一类。

---

## 6. 方案对比表（任务书 §4.5）

| 方案               | 解决什么                           | 不解决什么                                                   | 触发条件                     | 成本                          | 失败后果                                | 本项目                                                       |
| ------------------ | ---------------------------------- | ------------------------------------------------------------ | ---------------------------- | ----------------------------- | --------------------------------------- | ------------------------------------------------------------ |
| 状态机+重试+幂等写 | 单写者任务可中断可恢复、重放不重复 | 跨服务/跨库的事件通知可靠性                                  | 每次索引任务                 | 低（两张表+一套枚举）         | 漂移滞留，靠对账兜底                    | **采用** `[项目已实现]`                                      |
| 定时对账           | 双端 diff 发现并补偿一切残留       | 实时性；漂移发现前的暴露窗口                                 | 周期任务（默认关，需显式开） | 低~中（全量扫描成本随数据涨） | 不跑=永久漂移                           | **已实现，默认关闭** `[项目已实现]`，生产必须开或接受漂移【待验证：上线检查单】 |
| Outbox             | 业务变更与事件产生的同事务原子性   | 消费端执行成败；投递本身仍需重试                             | 出现 ≥1 个可靠事件消费者     | 中（表+中继+监控）            | 中继停=积压（可观测、可追赶）           | 未采用（无事件消费者）                                       |
| MQ                 | 多消费者解耦与扇出                 | **MQ 发送与 DB 事务不原子——裸发就是第二次双写**；消息积压、乱序 | ≥2 消费者或跨团队订阅        | 高（集群运维+死信体系）       | 发送失败=事件丢（无 Outbox 时不可恢复） | 未采用                                                       |

注意第 4 行：「加个 MQ」的直觉方案，实际是把双写窗口从"MySQL+PG"平移成"MySQL+MQ"，一秒钟都没解决——**MQ 只搬运输送问题，Outbox 才生产可靠事件，两者是配套不是二选一**。

---

## 7. 适用边界决策（三档可判定）

| 档位                                                 | 信号                                   | 选型                                        | 放弃的方案及原因                                             |
| ---------------------------------------------------- | -------------------------------------- | ------------------------------------------- | ------------------------------------------------------------ |
| ① 单应用、低频（<千级变更/日）、单消费者、RTO 分钟级 | 现状即此档                             | 状态机+重试+定时对账                        | 放弃 Outbox：多一张表+中继线程+运维面，换不来新能力；放弃 MQ：没有第二个消费者 |
| ② 跨服务、中高频、1~2 消费者、RTO 秒级               | 出现同步调用下游、或下游抱怨分钟级延迟 | Outbox + 轮询（简单优先）或 CDC（延迟优先） | 放弃裸发 MQ：事务与发送不原子，丢事件不可恢复；放弃实时双写：窗口不可管理 |
| ③ 多消费者（≥3）、异构系统、扇出、审计合规要求       | 事件本身成为产品能力                   | Outbox + MQ + 消费幂等 + 死信治理           | 放弃 Outbox 直连消费：N 个消费者 N 套拉取逻辑，耦合爆炸      |

**判据权重：消费者数量 > RTO > 变更频率。** 禁止仅凭"数据量大"或"改动多"上 MQ——数据量决定对账要不要增量化（§8），频率决定对账周期，只有消费者数量决定事件架构。本项目属档位 ①，这是不引入 Outbox 的**合理依据而非欠账**。

---

## 8. 排障手册：四类差异（对齐 `VectorReconciliationService`）

>
> 通用门禁：先 `dryRun` 出报告 → 人工核对样本 → 再非 `dryRun` 执行补偿。补偿动作全部限定 `document_id + version`（§5 版本隔离）。

### 8.1 孤儿向量（PG 有、MySQL 无活动对应）

- **定位**：PG 按 `(doc_key, doc_version)` 聚合行集，MySQL 按自然键聚合行集，应用层做差（跨库无法 JOIN，本项目实现即双查比对）；
- **判定**：先查该文档任务态——若 `INDEXING/PENDING/PARTIAL` 属正常进行中，**跳过不删**（Q5）；仅 `SUPERSEDED` 或 MySQL 无行才算孤儿；
- **修复**：`DELETE ... WHERE document_id=? AND version=?`，零 Embedding 成本；
- **验证**：重跑 reconcile 该 doc 计数=0；抽样检索确认内容版本正确。

### 8.2 缺失向量（MySQL 活动行、PG 无向量）

- **定位**：对账差集反方向；
- **判定**：确认非 CLEANUP 半途假阳性（看 SWITCH 时间 vs 对账时间窗）；
- **修复**：从 MySQL `content + content_sha` 重建，走稳定 ID Upsert；**必须带成本节流**——批大小上限 + 单轮重建条数预算（Embedding 是真金白银），超预算留到下轮；
- **验证**：双端 count 相等 + 用该文档内查询实测可召回 + `content_sha` 比对一致。

### 8.3 数量不一致（同 doc+version 双端行数不等）

- **定位**：按 `(doc_key, doc_version)` GROUP BY count 双端对比；
- **判定**：EMBED_WRITE 半途崩溃（PG 写了一部分）或 MySQL 关联行部分失败；
- **修复**：该版本整体作废——先按版本删 PG 残留，再重跑幂等 Upsert（重复补偿不产生新问题：删除按 ID 幂等、重建按唯一键幂等）；
- **验证**：计数一致 + 逐 chunk_no 对齐无空洞。

### 8.4 卡住任务（状态表超时未推进）

- **定位**：`状态 ∈ {PENDING, INDEXING, PARTIAL}` 且 `updated_at < now() - 阈值`；
- **判定**：区分进程还活（慢任务）与已死（崩溃残留）；
- **修复**：按 `IndexStage` 记录的最后完成阶段续跑（恢复语义已实现）；连续 N 次失败转 `FAILED` 停重试进人工队列（永久错误不重试：鉴权/参数/余额类，对齐 `EmbeddingFailureClassifier` 思路，重试责任层唯一、`RetryTemplate maxAttempts=1`）；
- **验证**：任务终态 + 四类差异复扫归零。

**增量对账 `[待验证：本项目为全量扫描实现]`**：全量 diff 不可扩展，标准演进是按 `updated_at` 水位线只扫变更段；水位线表自身丢行/回拨时兜底=回退全量或按 version 段重扫。可观测面板最小集：四类差异数、补偿成功率、卡住任务数、对账耗时、重试次数（若上 Outbox 再加积压深度）。

---

## 9. 必答思考题（逐题结论句，不许"视情况"）

**Q1 删除先删 MySQL 元数据还是先删 PG 向量？**
**结论：先摘 MySQL 可见性，后删 PG 向量。** 论证三条：① 成本不对称——重建向量=重新 Embedding（钱+分钟），误删孤儿向量=一条 DELETE（近零），所以宁可让崩溃残留落在"向量侧"（廉价可清）也不能落在"可见性侧"（危险）；② 安全不对称——先删向量后删元数据，崩溃留下"ACTIVE 元数据 + 无向量"的悬空（召回缺失，业务降级但安全）反过来就是"元数据已删 + 向量可召回"的**幽灵向量 = 数据泄露**；③ 与 `IndexStage` 更新顺序互为镜像——更新要"可回滚"故先写新后清理，删除要"不可召回"故先摘可见性后删物理行，两个顺序相反是目标相反，不是随意定的。

**Q2 什么信号需要 Outbox？**
**结论：判据权重 = 消费者数量 > 故障恢复时限(RTO) > 变更频率。** 消费者数量决定要不要"事件"这种解耦形态（0→1 个消费者才出现 Outbox 问题）；RTO 决定轮询还是 CDC；变更频率只决定对账周期与扫描成本，**永远不构成引入事件架构的理由**。本项目反例即答案：单应用 + 单消费者（PG 是 MySQL 的投影）+ 分钟级漂移可容忍 → 状态机+重试+对账闭环已足够，Outbox 的表、中继、积压监控全是无收益成本。

**Q3 重复删除事件会删错吗？**
**结论：会。** 稳定 ChunkId=内容+位置派生，新旧版本相同内容同位置 → 同 ID 复用；重放的删除按 ID 删时唯一键完全不设防——**唯一键防的是"重复插入"，管不了"误删"**。正确方案是删除前 CAS 三条件：`document_id + version` 锁定 + 服务端先判定该版本确为 `SUPERSEDED`，且执行窗口内任务不在 `INDEXING/PENDING/PARTIAL` 进行态（防止把正在切换的版本当旧的删掉）。

**Q4 事件发布成功、消费失败，谁重试？**
**结论：发布端只保证"事件可靠离开业务库"（同事务落库 + at-least-once 投递），从不保证消费端业务成功。** 消费失败归属：瞬时错误→broker 重投（退避、次数上限）；永久错误→死信 + 人工；兜底→消费方自己的对账。**任何时刻只有一个层在重试**（对齐本项目"重试收归应用层单一责任层"约定——多层重试会指数放大流量）。重复投递下消费侧幂等靠 event_id 幂等记录或 `aggregate_version > last_applied` 水位判断。职责边界一句话：**重试负责"再执行一次"，幂等负责"执行几次结果一样"**；只有前者会雪崩，只有后者不收敛。

**Q5 对账发现旧版本向量，立即删还是先判断？**
**结论：先判断，后删除。** 顺序：① 查任务态——`PENDING/INDEXING/PARTIAL` 说明更新进行中，PG 里"旧版本向量"其实是在服版本，删了就是制造缺失向量（触发重新 Embedding，有成本且造成召回空洞）；② 仅当 MySQL 已 COMPLETED 且切换发生、目标版本明确 `SUPERSEDED` 且 `document_id+version` 匹配才进清理；③ 先 `dryRun` 出清单再执行（对账语义已实现）。误判代价=把"进行中"当"漂移"，恢复代价最高的恰是不可逆的删除。

**Q6 为什么最终一致不能接受长期幽灵向量？**
**结论：最终一致描述的是收敛性质，不是暴露时限。** 三点必须拆开：① 暂时不一致（SLO 内收敛）≠ 永久漂移（无补偿器或补偿失败——本项目对账默认关闭时漂移就是没人管的）；② 召回缺失是业务降级，幽灵向量是**安全事件**——含权限边界内容的向量从产生到清除之间的每一秒都可被合法查询召回，泄露已经发生，"会被清掉"是事后追认；③ 所以对账周期=最大暴露窗口，普通内容可分钟级，涉权内容必须秒级——**撤权/删除路径上主动同步清理向量（事件驱动或同步双删+补偿），对账只做兜底**，绝不把安全边界外包给定时任务。

---

## 10. 故障注入与验证方案（**设计方案，未真实执行**）

注入手段基线：`AI_MODE=mock` 离线运行（零 Key 零外网），在 `indexChunks` 各阶段间埋测试专用异常开关（按配置类注入，不入生产路径）。

| #    | 场景                  | 操作前状态             | 注入位置                                    | 注入后 MySQL      | 注入后 PG          | 恢复动作                                    | 收敛结论与残留风险                                           |
| ---- | --------------------- | ---------------------- | ------------------------------------------- | ----------------- | ------------------ | ------------------------------------------- | ------------------------------------------------------------ |
| 1    | 正常增/改/删          | 空库                   | 无                                          | 状态行与指针正确  | 向量与活动版本一致 | —                                           | 基线；用 content_sha 抽验                                    |
| 2    | 悬空元数据            | v1 完成                | EMBED_WRITE 后、`upsertOnNaturalKey` 抛异常 | 无关联行/指针旧版 | 新版本向量已入库   | 重跑幂等 Upsert 或对账按版本清理            | 残留=未激活向量，不可召回，成本≈0                            |
| 3    | 幽灵向量              | v1 完成                | SWITCH 后、CLEANUP 抛异常                   | 指针=v2           | v1+v2 混存         | 对账识别 SUPERSEDED v1 并删除               | v1 在窗口内**可被无版本过滤的检索召回**→混答风险，验证活动指针必须生效 |
| 4    | 删除半途崩溃          | 已摘可见性、删向量 50% | 进程 kill -9                                | DEPRECATED 行在   | 残留部分向量       | 重启后按版本重放 DELETE                     | 崩溃点全部不可召回→安全性达标                                |
| 5    | 重复投递更新/删除事件 | 任意                   | 同一 payload 消费两遍                       | 不变              | 不变               | 幂等断言：第二次 no-op                      | 删除事件必须带版本 CAS（Q3），断言新复用 ID 行未被误删       |
| 6    | 旧事件晚到新事件      | v2 已应用              | 投 v1 的 DELAYED 事件                       | last_applied=2    | 不变               | `aggregate_version ≤ last_applied` 拒绝     | 旧事件零写入；断言日志含 OUT_OF_ORDER_REJECT                 |
| 7    | 对账四类全命中        | 手工造四类差异各 1     | reconcile dryRun→执行                       | 报告先于补偿      | 同                 | §8 四类修复动作                             | 复扫归零；dryRun 与实际执行差异符合实现语义                  |
| 8    | 补偿过程再故障        | 场景 7 执行中          | 补偿事务中途 kill                           | 状态可判定        | 半清理             | 重启后重放补偿（删除幂等/重建 Upsert 幂等） | 断言重复补偿不产生新问题、不覆盖 SWITCH 后新版本（版本隔离生效） |

每条用例四态判定：`PASS / FAIL / BLOCKED(缺环境) / NOT_RUN`；全部标注"待执行"直到真实跑通并落 `docs\evidence\D40\`。**本表是方案，不是结果。**

---

## 11. 官方溯源汇总

- Transaction Isolation | PostgreSQL 事务隔离与 MVCC：[https://www.postgresql.org/docs/18/transaction-iso.html](https://www.postgresql.org/docs/18/transaction-iso.html)
- MVCC Architecture | PostgreSQL 多版本并发控制：[https://www.postgresql.org/docs/18/mvcc.html](https://www.postgresql.org/docs/18/mvcc.html)
- pgvector README | pgvector 向量类型、索引与事务语义：[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)
- Unique Index Constraints | MySQL 唯一索引与重复键约束：[https://dev.mysql.com/doc/refman/8.0/en/create-table-secondary-indexes.html](https://dev.mysql.com/doc/refman/8.0/en/create-table-secondary-indexes.html)
- Transactional Outbox Pattern | 事务性发件箱模式：[https://microservices.io/patterns/data/transactional-outbox.html](https://microservices.io/patterns/data/transactional-outbox.html)
- Logical Replication | PostgreSQL 18 逻辑复制（CDC 机制基础）：[https://www.postgresql.org/docs/18/logical-replication.html](https://www.postgresql.org/docs/18/logical-replication.html)
- Debezium PostgreSQL Connector | Debezium PostgreSQL 逻辑复制连接器：[https://debezium.io/documentation/reference/stable/connectors/postgresql.html](https://debezium.io/documentation/reference/stable/connectors/postgresql.html)

---

## 验收自检

| 项                                                           | 状态 |
| ------------------------------------------------------------ | ---- |
| 六思考题均有结论句（Q1 先元数据后向量 / Q2 消费者数> RTO>频率 / Q3 唯一键防不了误删 / Q4 单层重试+幂等分责 / Q5 先判态再删 / Q6 安全边界不外包给定时任务） | ✅    |
| 两流程图含失败分支与恢复动作，节点与 IndexStage 一致，graph TD、双引号、无 `<br/>` | ✅    |
| 四类差异对齐 `VectorReconciliationService`，含 dryRun 语义   | ✅    |
| Outbox 表结构含 event_id / aggregate_version / uk_agg_ver / status / retry_count | ✅    |
| 三档适用边界 + 本项目不引入 Outbox 论证                      | ✅    |
| 故障注入 8 场景 × 6 字段，全部标注"设计方案未执行"           | ✅    |
| 事实分层无混用：绝对化表述已补前提（at-least-once 上限、对账默认关=漂移无人管） | ✅    |
| 状态枚举零虚构（无 DELETING/DELETED，ACTIVE 限定生命周期枚举） | ✅    |

落盘去向提醒（不落盘规则下的例外说明）：本笔记正文按要求只在本窗口输出；§10 用例真实执行后，证据脚本与日志按规范写入 `docs\evidence\D40\`。