# D30-PostgreSQL-pgvector 项目迭代全量说明

> 迭代名称：Day 30：PostgreSQL/pgvector——向量数据库接入与基础验证
> 工程模块：`D30_postgresqlAndpgvector`，物理包 `com.zl.d30`
> 文档定位：记录本迭代的目标、选型、架构、实现、验证与证据，作为可追溯的全量交付说明
> 编写视角：12 年 Java 架构师 + 6 年 AI 工程落地

---

## 一、迭代背景与目标

### 1.1 背景

D29 已完成本地哈希 Embedding（`LocalHashingEmbeddingModel`），但向量仅存在于内存，没有持久化能力。
D30 要在不改变 Embedding 模型的前提下，把向量真实写入 PostgreSQL 的 pgvector 扩展，并完成基础检索验证。

### 1.2 核心目标

1. 复核向量库选型：pgvector 为唯一向量库，不引入第二套，MySQL 业务库职责不变；
2. 完成 Docker Compose 启动、健康检查、扩展与维度验证；
3. 先核验模型真实输出再定 DDL，唯一决策 256 维；
4. 集成 Spring AI `PgVectorStore`，使用独立 PG 数据源，复用现有 EmbeddingModel；
5. 真实编译、真实写入、真实相似度查询，全部以真实结果为准。

### 1.3 硬边界（明确不做）

- 不做完整 RAG 问答、混合检索、Rerank、Agent、Tool、MCP、大规模导入、无关重构；
- 启动失败 / 扩展不可用 / 维度不符时不得宣称通过；
- 字符数 ≠ Token；连接成功 ≠ 检索成功。

---

## 二、选型复核与职责边界

### 2.1 选型结论

| 检查项 | 结论 |
| --- | --- |
| 改动前是否已有向量库 | 无任何 VectorStore 实现 |
| 唯一向量库 | PostgreSQL + pgvector |
| 是否引入第二套 | 否，不引入 Milvus / ES / Redis Vector |
| MySQL 职责 | 保持主业务库，不替换 |

### 2.2 职责边界

- MySQL：Prompt 模板、会话、消息等业务数据的权威存储，MyBatis-Plus 仍绑定 MySQL；
- PostgreSQL：仅承载向量存储，独立数据源、独立连接池、独立 JdbcTemplate；
- 两套数据源通过 `@Primary` 与 `@Qualifier` 显式隔离，互不串扰。

---

## 三、环境与版本基线（真实取证）

| 层 | 真实版本 |
| --- | --- |
| 主机 | CentOS 7.9.2009，内核 3.10.0-1160 |
| Docker Engine | 24.0.9 |
| Docker Compose | v2.24.7 |
| 容器 | `pgvector-pg18`（镜像 `pgvector/pgvector:pg18`） |
| PostgreSQL | 18.7 |
| pgvector | 0.8.7 |
| 容器状态 | Up (healthy)，`0.0.0.0:5432->5432` |
| 数据库 / 用户 | `spring_ai` / `postgres` |

工程依赖版本：

| 依赖 | 版本 | 来源 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | parent |
| Spring AI | 1.1.8 | BOM |
| spring-ai-starter-vector-store-pgvector | 1.1.8 | BOM |
| com.pgvector:pgvector | 0.1.6 | 传递依赖 |
| org.postgresql:postgresql | 42.7.11 | Boot BOM |
| HikariCP | 6.3.3 | Boot BOM |

---

## 四、整体架构

### 4.1 架构图（Typora 兼容 Mermaid）

```mermaid
graph TD
    subgraph client "客户端"
        A["HTTP 调用"]
    end
    subgraph app "Spring Boot 应用"
        B["PgVectorController"]
        C["PgVectorStoreService"]
        D["PgVectorStore"]
        E["LocalHashingEmbeddingModel"]
        F["pgVectorJdbcTemplate"]
        G["MySQL JdbcTemplate"]
    end
    subgraph ds "数据层"
        H["PostgreSQL pgvector"]
        I["MySQL 业务库"]
    end
    A --> B
    B --> C
    C --> D
    C --> F
    D --> E
    D --> F
    F --> H
    G --> I
```

### 4.2 数据流说明

1. 客户端请求进入 `PgVectorController`；
2. 控制器调用 `PgVectorStoreService`；
3. 写入时 `PgVectorStore` 经 EmbeddingModel 把文本转为 256 维向量；
4. 向量经专用 `pgVectorJdbcTemplate` 写入 PostgreSQL；
5. 检索时查询文本同样向量化，按余弦距离 `<=>` 排序后回读；
6. 业务链路的 MySQL JdbcTemplate 完全独立，不受影响。

---

## 五、256/384 维度唯一决策

### 5.1 决策依据

1. 本地哈希模型真实输出 256 维，`EmbeddingModel.dimensions()=256`；
2. `EmbeddingProperties` 内置默认 `dimensions=256`；
3. 全目录检索 `384` 零命中，外部 384 要求在仓库内不存在可落地引用。

### 5.2 决策结论

**唯一决策 256**。模型维度、配置维度、正式表 `vector(256)` 三者一致；
启动期断言不符即抛 `IllegalStateException`，fail-closed。

### 5.3 未来若需 384 的兼容方案

1. 先确认真实模型输出 384；
2. 以新 `vectorSpaceId`（新 modelVersion）标识新空间，不与 256 混写；
3. 新建 `vector(384)` 表与 HNSW 索引，存量内容重新 Embedding；
4. 切换 `indexVersion`，旧空间按策略保留或下线。

### 5.4 向量空间标识

```text
vectorSpaceId = provider | model | modelVersion | dimensions
默认：local | local-hashing-embedding | local-v1 | 256
```

---

## 六、工程结构层级树

```text
D30
├── pom.xml
└── D30_postgresqlAndpgvector
    ├── pom.xml
    └── src
        ├── main
        │   ├── java/com/zl/d30
        │   │   ├── D30SpringAiApplication.java
        │   │   ├── common
        │   │   └── document
        │   │       ├── chunk
        │   │       ├── embedding
        │   │       └── vectorstore
        │   │           ├── PgVectorStoreProperties.java
        │   │           ├── PgVectorStoreConfiguration.java
        │   │           ├── PgVectorStoreService.java
        │   │           ├── PgVectorController.java
        │   │           └── dto
        │   │               ├── VectorWriteRequest.java
        │   │               ├── VectorSearchRequest.java
        │   │               ├── VectorMatchView.java
        │   │               └── VectorStatsView.java
        │   └── resources
        │       ├── application.yml
        │       └── application-local.yml.example
        └── test/java/com/zl/d30/document/vectorstore
            ├── PgVectorStorePropertiesTest.java
            ├── PgVectorStoreConfigurationTest.java
            ├── PgVectorStoreServiceTest.java
            ├── PgVectorStoreIntegrationTest.java
            └── support
                ├── EnabledIfPgVector.java
                └── PgVectorReachableCondition.java
```

源码规模：main 189 个 Java（+8），test 45 个 `*Test.java`（+4），辅助类 4 个（+2）。

---

## 七、关键实现说明

### 7.1 双数据源装配

- `dataSource`：`@Primary`，由 `DataSourceProperties` 构建，绑定 MySQL，保证 MyBatis-Plus 与事务不被路由到 PG；
- `pgVectorDataSource`：非主 Hikari 数据源，连接池名 `pgvector-pool`，仅服务向量存储；
- `pgVectorJdbcTemplate`：`@Qualifier("pgVectorDataSource")`，与主 JdbcTemplate 隔离。

### 7.2 PgVectorStore 构造

```java
PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
    .schemaName(properties.getSchema())
    .vectorTableName(properties.getTable())
    .dimensions(dimensions)
    .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
    .indexType(PgVectorStore.PgIndexType.HNSW)
    .initializeSchema(properties.isInitializeSchema())
    .build();
```

- 距离类型固定 `COSINE_DISTANCE`，索引类型固定 `HNSW`，与 L2 归一化 + 余弦口径一致；
- `vectorTableValidationsEnabled` 采用 Builder 默认 false，表结构改由 catalog 独立核验。

### 7.3 REST 端点

| 方法 | 路径 | 职责 |
| --- | --- | --- |
| POST | `/api/v1/vector-store/documents` | 真实向量写入 |
| POST | `/api/v1/vector-store/search` | 余弦相似度检索 |
| GET | `/api/v1/vector-store/stats` | 表行数统计 |

统一返回 `Result<T>`，HTTP 恒 200，业务结果通过 `code` 区分；全局异常处理不回显堆栈与密钥。

---

## 八、验证结果（真实执行）

### 8.1 全量测试

```text
Tests run: 287, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

基线 266 + 新增 21 = 287，其中 6 个为连接真实 PostgreSQL 的集成测试。

### 8.2 真实端到端 HTTP

写入 3 条后，以「pgvector 向量 数据库 索引」检索：

| 排名 | 文本 | score | distance |
| --- | --- | --- | --- |
| 1 | postgres pgvector 向量数据库 存储与索引 | 0.741684 | 0.258316 |
| 2 | java spring boot 后端服务 开发与配置 | 0.199754 | 0.800247 |
| 3 | redis 缓存 会话 设计与过期策略 | 0.162400 | 0.837600 |

- 语义相关文档真实排名第一，score 严格降序；
- score + distance ≈ 1，印证余弦距离到相似度的换算；
- 错误维度写入被 DataAccessException 拒绝，fail-closed；
- 取证后已按标记 DELETE 3 条，回读 remaining=0，端口 8080 已释放。

---

## 九、证据与日志索引

### 9.1 证据目录 `docs/evidence/D30`

| 编号 | 内容 |
| --- | --- |
| 01 | 工程扫描 |
| 02 | 选型与维度决策 |
| 03 | Docker Compose 环境取证 |
| 04 | 健康检查取证 |
| 05 | pgvector 扩展取证 |
| 06 | 维度一致性核验 |
| 07 | 正式表 Schema 取证 |
| 08 | 真实写入与相似度检索 |
| 09 | 测试汇总 |
| 10 | 构建汇总 |
| 11 | 迭代汇总 |
| — | surefire-reports（91 文件扁平归档） |

### 9.2 日志目录 `logs/D30`

基线测试、SSH 环境、SQL 能力、HNSW 执行计划、编译、全量 clean test、应用运行日志均已归档。

---

## 十、注意事项与诚实缺口

### 10.1 注意事项

- 真实密码通过环境变量注入，证据与日志中一律脱敏；
- 长度口径为 UTF-16 code unit，不宣称 Token；
- 无数据库环境下集成测试由门控明确禁用，不拿跳过冒充通过。

### 10.2 诚实缺口

- 真实 MySQL 端到端 HTTP 上传未做（需先在 ai_db 执行 DDL）；
- 当前无鉴权，X-Dev-User 仅归属标识；
- 真实远程聊天模型未验证；扫描件仅提示 OCR，不实现 OCR。

---

## 十一、官方溯源

| 资料 | 链接 |
| --- | --- |
| pgvector 项目（GitHub） | https://github.com/pgvector/pgvector |
| PostgreSQL 官方文档 | https://www.postgresql.org/docs/ |
| Spring AI 参考文档 | https://docs.spring.io/spring-ai/reference/ |
| Spring AI PgVector Store | https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html |
| PostgreSQL JDBC Driver | https://jdbc.postgresql.org/ |

> 版本匹配说明：本环境 pgvector 0.8.7、Spring AI 1.1.8、PostgreSQL JDBC 42.7.11，
> 溯源时以对应版本的官方文档为准。
