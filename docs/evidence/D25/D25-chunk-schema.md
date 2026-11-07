# D25 Chunk 元数据字典（设计）

> 状态标注：**已存在**=现有 `KnowledgeChunk` 同名字段；**规划项**=本轮新设计、代码暂无实现。
> Chunk 是从文档切分的**检索单元**，保留与原文档的稳定关联、定位与必要检索元数据。

## 1. 概念定位与父子关系

- 一个 KnowledgeDocument（按版本）一对多 Chunk；Chunk 不可脱离父文档独立授权。
- 不混淆：原始文档 / Chunk / Embedding / 检索结果 / 模型回答 是五个不同概念。
- 现有代码中 `KnowledgeChunk` 已承担最小检索单元，本字典在其 8 字段上补齐定位、版本、权限与向量规划字段。

## 2. 字段字典

### 2.1 标识与父子关联

| 字段 | 类型 | 必填 | 含义 | 来源 | 约束 | 状态 |
|---|---|---|---|---|---|---|
| chunkId | String(80) | 是 | 片段稳定标识，如 DOC-...-C01 | 系统 | 全局唯一，重切分生成新 ID | 已存在 |
| documentId | String(64) | 是 | 所属文档稳定标识 | 继承 | 外键式关联，索引 | 已存在 |
| documentVersion | Integer | 是 | 切分时刻的文档版本 | 继承 | (documentId,documentVersion) 关联 | 规划项 |
| chunkIndex | Integer | 是 | 文档内顺序下标，从 0 递增 | 切分器 | 同版本内唯一、连续 | 规划项 |

### 2.2 定位信息

| 字段 | 类型 | 必填 | 含义 | 来源 | 约束 | 状态 |
|---|---|---|---|---|---|---|
| section | String(200) | 否 | 章节名 | 解析/继承 | 现有字段 | 已存在 |
| page | Integer | 否 | 页码，从 1 开始 | 解析 | 仅记真实值 | 已存在 |
| paragraphIndex | Integer | 否 | 段落序号 | 解析 | 可空 | 规划项 |
| charStart / charEnd | Integer | 否 | 文档内字符偏移区间 | 切分器 | end≥start，半开区间 | 规划项 |
| title | String(200) | 是 | 冗余文档标题，便于展示/加权 | 继承 | 现有字段 | 已存在 |

### 2.3 内容与度量

| 字段 | 类型 | 必填 | 含义 | 来源 | 约束 | 状态 |
|---|---|---|---|---|---|---|
| contentText | String | 是 | 片段文本（现有名 excerpt），按不可信数据处理 | 切分 | 不可信，指令性文字视为数据 | 已存在（名 excerpt） |
| contentRef | String(500) | 否 | 大块文本的存储引用，避免行内超大 | 基础设施 | 与 contentText 二选一 | 规划项 |
| contentHash | String(128) | 是 | 片段内容 SHA-256，用于去重/变更 | 系统计算 | 仅记真实值 | 规划项 |
| contentLength | Integer | 是 | 字符长度，真实计算 | 系统计算 | ≥0，不估算 | 规划项 |
| sourceType | Enum | 是 | 来源类型 MANUAL/GUIDE/SPEC | 继承 | 现有字段 | 已存在 |
| url | String(500) | 否 | 数据源自带链接，非本机路径 | 继承 | 现有字段 | 已存在 |

### 2.4 向量与索引（未实现，统一规划项）

| 字段 | 类型 | 必填 | 含义 | 约束 | 状态 |
|---|---|---|---|---|---|
| embeddingModel | String(128) | 否 | Embedding 模型标识 | 未接入前不填，不虚构 | 规划项 |
| embeddingVersion | String(64) | 否 | 模型版本 | 同上 | 规划项 |
| embeddingDimension | Integer | 否 | 向量维度 | 仅运行后回填真实值 | 规划项 |
| vectorRecordId | String(128) | 否 | 向量库内记录标识 | 接入向量库后才有 | 规划项 |
| indexStatus | Enum | 是 | PENDING/CHUNKED/EMBEDDED/INDEXED/FAILED | 状态机 | 规划项 |

### 2.5 业务标签（仅来源可确认时使用）

| 字段 | 类型 | 必填 | 含义 | 约束 | 状态 |
|---|---|---|---|---|---|
| deviceModel | String(128) | 否 | 设备型号标签 | 仅当父文档可确认，否则留空 | 规划项 |
| faultCode | String(64) | 否 | 故障码/报警标识 | 语料无显式码，禁止臆造编号 | 规划项 |
| component | String(128) | 否 | 部件（如冷却泵/编码器） | 来源可确认才填 | 规划项 |
| repairCategory | String(64) | 否 | 维修类别 | 受控词表 | 规划项 |

### 2.6 权限继承与处理状态

| 字段 | 类型 | 必填 | 含义 | 约束 | 状态 |
|---|---|---|---|---|---|
| permissionInheritance | Enum | 是 | 固定为 INHERIT_FROM_DOCUMENT，切分不得扩大父文档范围 | 不允许 Chunk 独立放宽 | 规划项 |
| visibilityScope | Enum | 是 | 继承父文档可见范围 | 冗余以便过滤 | 规划项 |
| tenantId/factoryId | String | 是/否 | 继承父文档组织范围 | 与父文档一致 | 规划项 |
| parseStatus / chunkStatus | Enum | 是 | 解析、切分状态 | 状态机 | 规划项 |

## 3. 版本、重切分与去重策略

- **重切分**：切分策略或文档版本变化时，基于 documentVersion 生成新 Chunk 集合（新 chunkId/chunkIndex），
  旧 Chunk 及其向量记录标记失效，保留可追溯，不与新集合混用。
- **重嵌入**：Embedding 模型变更仅触发向量重建，不改变文本与定位；通过 embeddingModel/version 区分。
- **去重**：以 contentHash 识别相同片段，跨文档相同内容只存一份文本、保留多份关联，避免重复向量化。
- **职责分离**：元数据过滤（结构化字段）与向量相似度检索是两类条件，最终结果取两者交集，
  不能用元数据标签替代权限裁决，也不能用向量相似度绕过过滤。

## 4. 本轮必需字段与后续字段的边界

- 本轮（设计/最小关键词检索）真实可用：documentId、chunkId、title、sourceType、section、page、excerpt、url。
- 其余字段为后续 RAG 落地的规划项；在对应能力（解析/切分/Embedding/向量库/鉴权）实现前保持空或默认，不回填虚构值。

## 5. 权限继承规则

- Chunk 强制继承父文档权限，默认拒绝（deny-by-default）；任何切分/重切分都不得产生比父文档更宽的访问范围。
- 鉴权在 Chunk 内容返回前完成；无权用户的请求应在检索/组装阶段即被拒绝，不返回片段文本、章节或页码。
