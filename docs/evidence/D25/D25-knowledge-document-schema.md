# D25 KnowledgeDocument 元数据字典（设计）

> 状态标注约定：**已存在**=当前代码/语料已有同名字段；**规划项**=本轮新设计、代码暂无实现。
> 本字典只形成设计，不自动建表、不生成生产代码。字段命名与现有 `KnowledgeChunk`/`CorpusDocument` 对齐处已注明。

## 1. 概念定位

KnowledgeDocument（知识文档）是**原始知识资料**的业务身份载体，记录来源、版本、权限与生命周期；
它不直接参与向量检索，检索单元是其下属 Chunk。一个 KnowledgeDocument 对应多个 Chunk（一对多）。

> 与现有代码关系：当前无文档级实体，仅 JSON 加载结构 `CorpusDocument(documentId,title,sourceType,url,chunks)`。
> 本字典在其基础上补齐企业级治理字段，新增字段均为规划项。

## 2. 字段字典

### 2.1 稳定标识

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| documentId | String(64) | 是 | 文档全局稳定标识，跨版本复用 | 系统生成/来源系统 | 不可变 | 唯一索引，如 DOC-CNC-COOLING-001 | 已存在 |
| docBusinessKey | String(128) | 否 | 外部业务系统主键，用于幂等对接 | 来源系统 | 接入后不可变 | 普通索引，允许为空 | 规划项 |

### 2.2 业务信息

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| title | String(200) | 是 | 文档标题/文件名 | 人工/来源 | 可改（留痕） | 非空，可搜索 | 已存在 |
| documentType | Enum | 是 | 文档类型，沿用现有 sourceType 取值 MANUAL/GUIDE/SPEC | 人工分类 | 可改 | 枚举索引 | 已存在（名 sourceType） |
| deviceModel | String(128) | 否 | 适用设备型号，多值用受控列表 | 人工/解析 | 可改 | 过滤索引；不确定留空不臆测 | 规划项 |
| deviceSeries | String(128) | 否 | 适用设备系列 | 人工 | 可改 | 过滤索引 | 规划项 |
| applicabilityScope | String(255) | 否 | 适用工况/范围说明 | 人工 | 可改 | 文本 | 规划项 |

### 2.3 来源追踪

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| sourceSystem | String(64) | 是 | 来源系统标识（如 PLM/MES/手工） | 接入层 | 不可变 | 索引 | 规划项 |
| sourceFileId | String(128) | 否 | 原始文件在来源系统的标识 | 来源系统 | 不可变 | 普通索引 | 规划项 |
| fileName | String(255) | 是 | 原始文件名（含扩展名） | 上传/接入 | 可改 | 非空 | 规划项 |
| storageLocator | String(500) | 否 | 内部存储定位（对象存储 key，非本机绝对路径） | 基础设施 | 可改 | 禁止暴露本机绝对路径 | 规划项 |
| sourceUrl | String(500) | 否 | 数据源自带有效链接 | 来源系统 | 可改 | 现有字段名 url | 已存在（名 url） |

### 2.4 版本与有效性

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| version | Integer | 是 | 文档版本号，从 1 递增 | 系统 | 仅追加 | (documentId,version) 唯一 | 规划项 |
| effectiveAt | OffsetDateTime | 否 | 生效时间 | 人工 | 可改 | 时间索引 | 规划项 |
| expireAt | OffsetDateTime | 否 | 失效时间，空表示长期有效 | 人工 | 可改 | 检索需过滤未生效/已失效 | 规划项 |
| effectiveStatus | Enum | 是 | DRAFT/IN_REVIEW/PUBLISHED/ARCHIVED | 状态机 | 受控流转 | 过滤索引 | 规划项 |

### 2.5 组织与权限

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| tenantId | String(64) | 是 | 租户标识（当前单租户可给默认值） | 鉴权/接入 | 不可变 | 过滤索引 | 规划项 |
| factoryId | String(64) | 否 | 工厂/厂区范围 | 组织数据 | 可改 | 过滤索引 | 规划项 |
| orgId | String(64) | 否 | 组织范围 | 组织数据 | 可改 | 过滤索引 | 规划项 |
| visibilityScope | Enum | 是 | PRIVATE/ORG/PUBLIC 可见范围 | 人工 | 可改 | 过滤 | 规划项 |
| permissionPolicyRef | String(128) | 否 | 权限策略引用（策略由鉴权模块维护） | 鉴权模块 | 可改 | 仅引用，不在此复制规则 | 规划项 |

### 2.6 生命周期

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| createdAt | OffsetDateTime | 是 | 创建时间 | 系统 | 不可变 | 索引 | 规划项 |
| updatedAt | OffsetDateTime | 是 | 最近更新时间 | 系统 | 系统维护 | — | 规划项 |
| reviewedAt / publishedAt / archivedAt | OffsetDateTime | 否 | 审核/发布/归档时间 | 状态机 | 受控 | — | 规划项 |
| lifecycleStatus | Enum | 是 | 生命周期状态，与 effectiveStatus 联动 | 状态机 | 受控流转 | 索引 | 规划项 |

### 2.7 内容治理与扩展

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引 | 状态 |
|---|---|---|---|---|---|---|---|
| language | String(16) | 否 | 内容语言（如 zh-CN） | 解析/人工 | 可改 | BCP-47 | 规划项 |
| summary | String(1000) | 否 | 内容摘要 | 解析/人工 | 可改 | 文本 | 规划项 |
| contentHash | String(128) | 是 | 原始内容哈希（SHA-256），用于变更检测/去重 | 系统计算 | 随内容变 | 仅记真实计算值 | 规划项 |
| parseStatus | Enum | 是 | PENDING/PARSED/FAILED 解析状态 | 解析链路 | 受控 | 过滤 | 规划项 |
| extensionAttributes | Map<String,Object> | 否 | 受控扩展，键需登记，禁止自由膨胀 | 真实需求驱动 | 可改 | 键白名单 | 规划项 |

## 3. 权限数据来源与校验责任

- 权限字段（tenantId/factoryId/visibilityScope/permissionPolicyRef）是**策略输入与过滤条件**，不是可被检索结果绕过的标签。
- 真正的访问裁决由**独立鉴权能力**依据当前用户身份与 permissionPolicyRef 执行；当前项目无该能力，
  故本字典只定义接口契约，校验责任标“待鉴权模块落地”，禁止在文档中宣称权限已强制生效。
- 权限裁决必须在**证据/Chunk 暴露之前**完成（先鉴权后检索/组装）。

## 4. 更新失效与追溯策略

- 文档内容变更（contentHash 变化）生成新版本：旧版本 Chunk、向量记录与缓存按版本**标记失效并可追溯**，
  检索默认只命中 PUBLISHED 且在有效期内的版本；历史版本保留用于审计与评测回放。
- 下线/归档：级联将下属 Chunk 从可检索集合移除（向量删除 + 缓存失效），不物理删除原始记录。
- 去重：以 contentHash + documentId 识别重复内容，避免重复接入与重复计费。

## 5. 隐私约束

- 仅记录治理所必需的最小数据；不持久化无关个人敏感信息；文件名/摘要如含个人信息需脱敏。
- 存储定位使用对象存储 key，不写入本机绝对路径，不入库真实密钥。
