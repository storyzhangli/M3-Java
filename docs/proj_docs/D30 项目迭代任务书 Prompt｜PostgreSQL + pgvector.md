# D30 项目迭代任务书 Prompt｜PostgreSQL + pgvector

## 一、角色与任务定位

你是一名资深 Java 后端架构师、Spring AI 工程师、PostgreSQL/pgvector 工程师。

本次任务是 M3-Java 项目的 Day 30 迭代：

**Day 30：PostgreSQL/pgvector——向量数据库接入与基础验证**

本轮目标不是单独搭建一个 PostgreSQL Demo，而是在现有 M3-Java 工程基础上，完成向量数据库技术选型复核、PostgreSQL/pgvector 环境验证、Spring AI 集成方案设计及必要的工程实现，为后续 RAG 向量写入、相似度检索和知识库问答建立可靠基础。

必须遵守以下原则：

1. 先扫描、再设计、后实施，不得脱离仓库实际情况设计。
2. 优先沿用现有工程架构、编码规范、技术版本和已验证设计。
3. 不重复实现已有功能，不进行与本次目标无关的重构。
4. 所有实现必须有真实代码、编译、测试或运行证据支撑。
5. 不得将数据库连接成功等同于向量检索成功。
6. 不得在数据库启动失败、扩展不可用或向量维度不匹配时宣称功能验收通过。
7. 避免无差别读取整个仓库，控制 AI 上下文和 Token 消耗。

---

## 二、项目路径与执行范围

### 2.1 项目根目录

```text
E:\AI\project\AI-Learning-one\M3-Java\D30
```

首先扫描项目根目录中的 `pom.xml`，确认实际 Maven 工程结构、父子模块关系、依赖管理和版本基线。

### 2.2 扫描与基础骨架目录

```text
E:\AI\project\AI-Learning-one\M3-Java\D30\D30_postgresqlAndpgvector
```

该目录是本轮 D30 的主要扫描与基础骨架目录。

执行要求：

- 先确认目录是否存在及其完整结构。
- 识别已有 Java 源码、配置、测试、SQL、Docker 文件和 Markdown 文档。
- 识别已有功能、未完成实现、TODO、设计说明和历史证据。
- 判断该目录是独立 Maven 工程、现有模块，还是待完善的基础骨架。
- 不得未经确认擅自创建重复模块或改变现有模块结构。

### 2.3 历史工程参考范围

优先参考 M3-Java 已有的 D25–D29 工程成果，重点关注：

- D25：RAG 基础架构、知识检索、问答编排、引用校验。
- D26：文档登记、文件导入、元数据、文档状态管理。
- D27：PDF 解析、页面文本持久化、来源定位。
- D28：文本清洗、Chunk 切分、来源映射。
- D29：EmbeddingModel 抽象、向量生成、向量校验、模型元数据、向量空间兼容性。

历史工程仅作为设计和代码参考，不得未经分析直接复制整个模块。

优先查看与 D30 直接相关的 POM、Embedding 实现、配置类、向量元数据、验证器、测试和技术文档。

如果历史代码与当前 D30 基础骨架存在差异，以当前 D30 实际工程状态为准，并说明差异。

---

## 三、本次核心功能需求

### 3.1 PostgreSQL + pgvector 技术选型

默认规则：

- 如果仓库不存在已经落地的向量存储方案，则采用 PostgreSQL + pgvector。
- PostgreSQL + pgvector 是本项目本阶段唯一向量数据库。
- 如果仓库已经存在向量存储决策或实际实现，必须先检查设计依据、实现完整性和兼容性，复核后沿用合理方案。
- 不得为了引入 PostgreSQL 而重复建设已有功能。
- 不得同时引入 Milvus、Elasticsearch、Redis Vector 等其他向量数据库作为第二套向量存储方案。
- MySQL 现有业务数据存储职责原则上保持不变。本轮 PostgreSQL/pgvector 作为向量存储能力接入，不得未经设计替换现有 MySQL 业务数据库。

需要形成明确的技术选型结论：

1. 当前仓库是否已有向量数据库实现？
2. 是否已有 PostgreSQL 或 pgvector 相关依赖、配置、DDL？
3. 当前向量存储设计是否可以直接沿用？
4. 是否需要新增 Spring AI PostgreSQL/pgvector 集成？
5. PostgreSQL 与现有 MySQL 的职责边界是什么？
6. 本轮实际采用的向量存储方案及原因是什么？

以上结论必须基于实际代码、POM、配置或文档证据，不得凭空判断。

### 3.2 PostgreSQL + pgvector 环境基线

以下为已验证环境，必须严格遵守，不得擅自假设其他配置。

#### A. 虚拟机

| 配置            | 已验证值                                     |
| --------------- | -------------------------------------------- |
| 操作系统        | CentOS 7.9.2009                              |
| Kernel          | 3.10.0-1160                                  |
| 网络            | VMware NAT 内网                              |
| IP              | 192.168.200.153                              |
| 账户/密码(凭据) | root/itcast                                  |
| SSH 端口        | 22                                           |
| 网络访问        | Windows 宿主机可访问，虚拟机不可直接访问公网 |
| CPU             | 4 Core                                       |
| 内存            | 约 3.7 GB                                    |
| 可用磁盘        | 约 17 GB                                     |

#### B. Docker 环境

| 配置           | 已验证值                |
| -------------- | ----------------------- |
| Docker Engine  | 24.0.9                  |
| Docker API     | 1.43                    |
| Docker Compose | v2.24.7                 |
| containerd     | 1.6.33                  |
| Storage Driver | overlay2                |
| Cgroup         | v1                      |
| Compose 目录   | `/root/pgvector-stack/` |

注意：

- 使用 `docker compose` 命令。
- 不使用旧版 `docker-compose` 命令。
- 已配置阿里云镜像加速器。
- 不得擅自升级 Docker、Compose 或 containerd。
- 不得为了本轮迭代重新安装 Docker。

#### C. PostgreSQL + pgvector

| 配置            | 已验证值                                           |
| --------------- | -------------------------------------------------- |
| 容器名称        | `pgvector-pg18`                                    |
| 镜像            | `pgvector/pgvector:pg18`                           |
| PostgreSQL 版本 | 18.7                                               |
| pgvector 版本   | 0.8.7                                              |
| Host            | `192.168.200.153`                                  |
| Port            | `5432`                                             |
| Database        | `spring_ai`                                        |
| Username        | `postgres`                                         |
| Password        | Postgres@2026                                      |
| JDBC URL        | `jdbc:postgresql://192.168.200.153:5432/spring_ai` |
| 时区            | Asia/Shanghai                                      |
| 数据卷          | `pgvector-stack_pg18_data`                         |

pgvector 扩展已经启用，实际版本为 0.8.7。

数据库连接配置示例：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://192.168.200.153:5432/spring_ai
    username: postgres
    password: ${PGVECTOR_DB_PASSWORD:Postgres@2026}
```

以上配置仅用于说明连接参数。最终配置必须结合当前 D30 的 Spring Boot、Spring AI 和数据源设计确定。

禁止将数据库密码直接写入：

- 提交到 Git 的配置文件；
- 技术文档中的真实凭据；
- 日志和证据文件。

通过环境变量或其他安全注入方式提供真实密码。

### 3.3 Docker Compose 部署与健康检查

本轮必须验证 PostgreSQL/pgvector 容器环境，而不是仅提供一份 Docker Compose 文件。

执行顺序：

1. 检查虚拟机网络连通性。
2. 检查 Docker Engine 与 Docker Compose 版本。
3. 检查 `/root/pgvector-stack/` 现有 Compose 文件。
4. 检查当前容器状态。
5. 检查 PostgreSQL 日志。
6. 检查 PostgreSQL 实际版本。
7. 检查 pgvector 扩展及版本。
8. 检查数据库连接。
9. 检查向量字段和向量运算能力。

常用检查命令：

```bash
docker version

docker compose version

cd /root/pgvector-stack/

docker compose ps

docker exec pgvector-pg18 pg_isready -U postgres -d spring_ai

docker exec -it pgvector-pg18 psql -U postgres -d spring_ai
```

查看日志：

```bash
docker compose logs --tail=200 pgvector-pg18
```

如容器名称与 Compose 服务名称不同，先检查实际 Compose 服务名称，再使用正确命令。

#### 部署约束

1. 优先复用已经部署的 PostgreSQL/pgvector 容器。
2. 只有在确认容器未启动且需要恢复服务时，才考虑执行 `docker compose up -d`。
3. 不得无必要执行 `docker compose down`。
4. 不得无必要重建容器或删除数据卷。
5. 不得修改现有 Compose 文件的端口映射和数据卷挂载，除非确有必要且经过用户明确授权。
6. 不得删除历史容器、历史镜像或其他业务数据。
7. 不得因为健康检查失败而反复重建整个 Docker 环境。
8. 如果数据库无法启动，先收集日志、分析根因并报告，不得继续假设数据库可用。

**特别说明：当前虚拟机已有历史容器和镜像，不允许清理或删除与本轮无关的资源。**

### 3.4 pgvector 扩展与基础 SQL 验证

需要验证以下内容：

- PostgreSQL 服务可用。
- `vector` 扩展存在且可启用。
- pgvector 版本正确。
- 可以创建 `vector(N)` 类型字段。
- 可以插入合法向量。
- 可以执行向量距离运算。
- 可以执行余弦相似度排序查询。

基础验证 SQL：

```sql
-- 1. 检查 PostgreSQL 版本
SELECT version();

-- 2. 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 3. 检查扩展版本
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';

-- 4. 创建独立验证表
CREATE TABLE IF NOT EXISTS d30_vector_test (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    embedding VECTOR(3) NOT NULL
);

-- 5. 插入测试向量
INSERT INTO d30_vector_test (content, embedding)
VALUES
    ('测试向量 A', '[1,0,0]'),
    ('测试向量 B', '[0.9,0.1,0]'),
    ('测试向量 C', '[0,1,0]');

-- 6. 验证向量余弦距离
SELECT
    id,
    content,
    embedding <=> '[1,0,0]'::vector AS cosine_distance
FROM d30_vector_test
ORDER BY embedding <=> '[1,0,0]'::vector
LIMIT 3;
```

说明：

- 上述 `VECTOR(3)` 仅用于独立数据库基础能力验证。
- 不代表项目实际 Embedding 维度。
- 正式项目向量表必须使用实际 Embedding 输出维度。
- 测试表必须使用独立、明确的表名，不能覆盖历史业务表。
- 测试数据清理必须限定在本轮专用验证对象范围内，并保留可追溯证据；不得清理用户已有数据。

### 3.5 Embedding 维度与向量空间兼容性

这是本轮最重要的技术风险之一，必须重点检查。

#### A. 历史代码情况

D29 历史工程中存在以下已知信息：

- `EmbeddingProperties` 默认维度为 256。
- `LocalHashingEmbeddingModel` 是基于 signed feature hashing 的词面向量模型。
- D29 的部分证据记录了 256 维配置。
- 当前环境上下文中另有本地 Embedding 384 维的配置要求。

因此，**256 与 384 之间存在待核实差异**。

不能直接假定：

- 当前模型已经输出 384 维；
- D29 的默认配置已经被修改；
- 数据库字段应该无条件使用 `VECTOR(384)`；
- 只修改 `application.yml` 就能完成维度切换。

#### B. 必须检查的内容

实际扫描并确认：

1. `EmbeddingProperties` 的默认维度。
2. `LocalHashingEmbeddingModel` 的真实输出维度。
3. `EmbeddingModelMetadata` 中的维度信息。
4. `VectorValidator` 的校验逻辑。
5. `vectorSpaceId` 的生成逻辑。
6. 当前运行配置是否覆盖默认值。
7. 实际 Embedding 返回向量的长度。
8. 是否存在不同模型或不同维度向量混用的风险。

#### C. 维度决策

根据真实代码和运行结果形成唯一、明确的维度决策。

如果本轮采用 384 维：

- 必须确保 Embedding 模型真实输出 384 维。
- 必须同步检查模型配置、向量校验、元数据、vectorSpaceId 和数据库 DDL。
- 必须通过测试证明输出维度与数据库字段维度一致。
- 不得仅将数据库字段修改为 `VECTOR(384)`。

如果本轮继续采用 256 维：

- 必须证明当前运行模型真实输出 256 维。
- 正式向量字段必须使用 `VECTOR(256)`。
- 说明为何当前阶段继续采用 256 维。
- 明确与外部 384 维配置要求的差异和后续兼容方案。

如果实际模型维度无法确认：

- 暂停正式向量表 DDL 和数据写入。
- 先完成模型输出维度核验。
- 将待确认事项和阻塞原因写入证据。
- 不得自行猜测维度。

#### D. 向量空间兼容性

向量维度相同不代表向量空间兼容。

至少考虑：

- 模型名称；
- 模型版本；
- 向量维度；
- 向量生成方式；
- 向量归一化规则；
- 特征提取规则；
- vectorSpaceId。

不同模型、不同版本或不同向量空间的向量不得混合进行相似度检索。

如果 D29 已有向量空间兼容性机制，应优先沿用，不得另建一套重复机制。

### 3.6 Spring AI 与 PostgreSQL/pgvector 集成

根据 D30 实际 POM 和现有架构确定集成方式。

重点检查：

- 当前 Spring Boot 版本；
- 当前 Spring AI 版本；
- Spring AI PostgreSQL/PGVector 相关 Starter；
- PostgreSQL JDBC Driver；
- 依赖版本管理；
- 当前 EmbeddingModel 实现；
- 向量存储配置；
- 数据源配置；
- 向量表初始化机制。

依赖选择要求：

1. 优先使用与当前 Spring AI 版本兼容的官方组件。
2. 不得混用不同 Spring AI 版本的 Starter。
3. 不得直接复制旧版本配置。
4. 不得为了集成 pgvector 随意升级 Spring Boot 或 Spring AI。
5. 先检查现有依赖树，避免重复引入 JDBC Driver 或其他依赖。
6. 需要新增依赖时，说明依赖用途、版本来源和兼容性依据。

如果使用 Spring AI 的 `PgVectorStore`：

- 检查当前版本对应的 API 和配置项。
- 确认 EmbeddingModel 与 PgVectorStore 使用同一个维度约束。
- 确认向量表初始化行为。
- 确认 Schema 初始化是否由程序执行。
- 确认距离类型、索引类型和元数据存储方式。
- 不得假设不同 Spring AI 版本的配置属性完全相同。

如果现有 D29 向量服务已经有合理的抽象，应评估如何将 PostgreSQL/pgvector 接入该抽象，而不是无理由推翻原有设计。

### 3.7 向量索引与相似度查询

本轮根据实际功能范围完成必要的索引与查询验证。

优先使用余弦距离：

```sql
embedding <=> '[1,0,0]'::vector
```

如需创建 HNSW 索引，应确认实际向量列维度、距离类型和索引操作符类。

余弦距离索引示例：

```sql
CREATE INDEX IF NOT EXISTS idx_d30_embedding_cosine
ON d30_vector_test
USING hnsw (embedding vector_cosine_ops);
```

注意：

- 索引应作用于正式向量表时，必须先确认正式表结构。
- 不得将 3 维示例表当作正式业务表。
- HNSW 索引的存在不等于检索结果正确。
- 小规模测试数据不一定能够证明索引被查询计划实际使用。
- 如果本轮不具备合理的索引效果验证条件，应如实说明，不得夸大性能结论。

### 3.8 功能边界

本轮核心范围：

1. PostgreSQL/pgvector 方案复核。
2. PostgreSQL 容器环境验证。
3. Docker Compose 与健康检查验证。
4. pgvector 扩展与版本验证。
5. Embedding 维度核验。
6. PostgreSQL JDBC 集成。
7. Spring AI pgvector 集成方案及必要实现。
8. 向量表结构与维度验证。
9. 基础向量写入与相似度查询验证。
10. 技术文档、证据和日志。

不应无理由扩展为：

- 完整 RAG 业务重构；
- 大规模知识库数据导入；
- PDF/OCR 再开发；
- 全量 Chunk 算法重构；
- 更换 Embedding 模型；
- 引入其他向量数据库；
- 全面改造现有 MySQL 业务；
- Agent、Tool Calling、MCP 等后续功能；
- 与 D30 无关的前端开发。

如果发现后续功能所需的前置问题，只记录风险和建议，不擅自扩大本轮范围。

---

## 四、工程实施要求

### 4.1 实施前扫描

先执行轻量级扫描，不要一开始读取整个仓库。

建议扫描顺序：

1. 项目根目录文件结构。
2. 根目录 `pom.xml`。
3. D30 基础骨架目录结构。
4. D30 所有 POM。
5. D30 `application.yml` 及相关配置。
6. D30 核心 Java 源码。
7. D30 测试代码。
8. D29 EmbeddingModel、向量校验和元数据相关实现。
9. D29 对应证据和设计文档。
10. Git 当前分支、HEAD、工作区状态。

使用脚本统计文件清单、Java 文件数量和模块关系。

只读取与本次任务相关的文件。需要深入分析时，再按文件和功能逐步展开。

### 4.2 实施前设计

实施前必须输出简明设计结论：

- 当前工程结构；
- 已有向量存储能力；
- 现有 EmbeddingModel 实现；
- 实际维度；
- PostgreSQL/pgvector 接入方式；
- 需要修改的文件；
- 需要新增的文件；
- 数据库表结构设计；
- 配置和凭据注入方式；
- 测试策略；
- 验收证据。

如果发现设计冲突、数据风险、不可逆操作或环境限制，先停止相关操作并说明，不得擅自执行高风险变更。

### 4.3 最小必要改动

严格遵循：

- 优先复用现有代码。
- 优先修改必要配置和少量核心类。
- 不重复创建已有工具类。
- 不随意改变公共 API。
- 不修改与 D30 无关的模块。
- 不做无需求依据的架构升级。
- 不删除已有历史代码或文档。
- 不覆盖用户已有配置。
- 不将真实密码和 API Key 写入 Git。

每次修改后记录变更文件及原因。

### 4.4 配置管理

数据库连接配置和 Embedding 配置必须区分：

- 数据库地址；
- 数据库名称；
- 数据库用户名；
- 数据库密码；
- Embedding 模型名称；
- Embedding 维度；
- 向量空间标识；
- 向量索引与距离配置。

敏感配置通过环境变量注入。

需要新增本地配置示例时，使用不含真实密码的占位符。

检查 `.gitignore`，避免本地密钥文件、日志、临时文件和真实运行配置意外提交。

### 4.5 测试要求

至少根据本轮实际实现覆盖：

1. 配置绑定测试。
2. Embedding 实际输出维度测试。
3. 向量维度校验测试。
4. PostgreSQL 连接测试。
5. pgvector 扩展检查。
6. 向量表创建或初始化验证。
7. 向量写入测试。
8. 余弦距离查询测试。
9. 异常连接或数据库不可用场景。
10. 向量维度不匹配场景。

测试应尽量隔离，避免依赖用户已有数据。

如果使用集成测试，明确测试依赖的数据库地址、环境变量和启动条件。

不得为了让测试通过而跳过关键断言、屏蔽异常或伪造返回结果。

---

## 五、验收标准

本轮必须按以下层级分别验收，不得混为一个结果。

| 验收层级 | 验收内容      | 通过条件                                        |
| -------- | ------------- | ----------------------------------------------- |
| L1       | 工程扫描      | POM、模块结构、已有向量方案已核验               |
| L2       | Docker 环境   | Docker Engine、Compose 和目标容器状态有真实证据 |
| L3       | 数据库健康    | PostgreSQL 可连接，健康检查通过                 |
| L4       | pgvector 扩展 | 扩展存在且版本核验正确                          |
| L5       | 维度一致性    | Embedding 输出维度与正式向量字段维度一致        |
| L6       | JDBC 集成     | Java 应用能够连接 PostgreSQL                    |
| L7       | 向量写入      | 实际向量可以成功写入                            |
| L8       | 相似度查询    | 实际向量能够执行相似度查询并返回合理结果        |
| L9       | 工程质量      | Maven 编译、相关测试通过                        |
| L10      | 文档证据      | 技术文档、测试证据、日志和变更记录完整          |

### 5.1 严格验收规则

以下情况不得判定为完整通过：

- Docker 启动失败；
- PostgreSQL 连接失败；
- pgvector 扩展不可用；
- Embedding 维度无法确认；
- Embedding 输出维度与数据库字段不一致；
- 向量写入失败；
- 相似度查询失败；
- 只完成配置，没有真实运行验证；
- 只完成单元测试，没有必要的数据库集成验证。

允许部分完成，但必须明确标注：

- 已通过；
- 未通过；
- 未执行；
- 被阻塞；
- 阻塞原因；
- 后续处理建议。

**禁止将“代码已经写好”“配置已经添加”表述为“数据库检索已经通过”。**

---

## 六、文档交付要求

### 6.1 技术文档输出目录

```text
E:\AI\project\AI-Learning-one\M3-Java\docs\proj_docs
```

本轮必须交付两份独立 Markdown 文档。

#### 文档一：项目迭代全量说明

建议文件名：

```text
D30-PostgreSQL-pgvector项目迭代全量说明.md
```

内容至少包含：

1. D30 迭代背景与目标。
2. 原有工程结构和技术栈。
3. 现有向量存储方案复核。
4. PostgreSQL + pgvector 技术选型。
5. Docker 部署与环境验证。
6. 数据库连接配置。
7. pgvector 扩展与版本。
8. Embedding 维度核验及最终决策。
9. 向量空间兼容性设计。
10. Spring AI 集成架构。
11. 数据库表结构与字段说明。
12. 向量写入与相似度查询实现。
13. 关键代码与调用链路。
14. 配置项及环境变量说明。
15. 实际操作步骤。
16. 编译和测试结果。
17. 失败场景及异常处理。
18. 本轮完成情况与遗留问题。

要求：

- 记录实际实现，不得写成计划书。
- 对关键设计说明为什么这样做。
- 关键代码说明其职责及调用关系。
- 对实际执行过的操作给出真实命令和结果。
- 不得虚构运行结果。

#### 文档二：技术说明与操作实践指南

建议文件名：

```text
D30-PostgreSQL-pgvector技术说明与操作实践指南.md
```

内容至少包含：

1. PostgreSQL 与 pgvector 基础原理。
2. pgvector 的数据类型与向量存储方式。
3. Embedding 向量维度的意义。
4. 向量空间兼容性。
5. 余弦距离与 `<=>` 运算符。
6. HNSW 索引原理与适用场景。
7. Spring AI 与 PgVectorStore 集成说明。
8. Docker Compose 环境操作。
9. PostgreSQL 连接和健康检查。
10. pgvector 扩展检查。
11. SQL 创建、写入、查询实践。
12. Java 侧向量写入和检索实践。
13. 常见错误及排查方法。
14. 数据库不可用时的处理。
15. 向量维度不匹配的处理。
16. 日常维护与验证命令。

要求：

- 以可实际操作为导向。
- 命令、SQL、配置必须与本轮实际环境一致。
- 区分基础示例与正式项目实现。
- 对危险操作给出明确警告。
- 不得将未经验证的内容写成已验证实践。

### 6.2 文档引用规范

技术文档中使用外部资料时，优先采用官方文档。

链接格式：

```text
English Original Title | 准确中文翻译标题：[完整 URL](完整 URL)
```

要求：

- 优先使用当前 Spring AI 版本对应的官方文档。
- PostgreSQL 和 pgvector 使用版本匹配的官方资料。
- 不使用无法核实的 URL。
- 不使用带追踪参数的链接。
- 对关键技术结论提供对应资料来源。

---

## 七、证据目录

统一目录：

```text
E:\AI\project\AI-Learning-one\M3-Java\docs\evidence
```

本轮证据建议放在：

```text
docs/evidence/D30/
```

至少保存：

```text
D30/
├── 01-project-scan.md
├── 02-vector-storage-decision.md
├── 03-docker-environment.md
├── 04-postgresql-health.md
├── 05-pgvector-extension.md
├── 06-embedding-dimension.md
├── 07-database-schema.md
├── 08-vector-insert-query.md
├── 09-test-summary.md
├── 10-build-summary.md
└── 11-iteration-summary.md
```

证据内容以实际执行结果为准。

需要保留：

- 实际命令；
- 实际输出；
- 执行时间；
- 执行环境；
- 测试结果；
- 失败信息；
- 关键决策依据。

不得用手工编造的文本替代真实执行结果。

截图仅在确有必要时保存，不需要为了凑数量而截图。

---

## 八、临时文件目录

统一目录：

```text
E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs
```

所有临时文件统一放入此目录，包括：

- 扫描脚本；
- PowerShell 脚本；
- SQL 临时文件；
- 临时 Markdown；
- 中间分析结果；
- 临时配置；
- 调试辅助文件。

特别注意：

**禁止 AI 助手自动删除、清理、覆盖或批量整理 `docs/temp_docs/` 中的文件。**

临时文件由用户自行决定是否删除。

如果临时文件已存在：

- 先检查是否与本轮任务有关。
- 不得擅自覆盖。
- 新文件采用明确的 D30 前缀和不同文件名。
- 不得删除历史临时文件。

---

## 九、日志目录

统一目录：

```text
E:\AI\project\AI-Learning-one\M3-Java\logs
```

本轮日志建议：

```text
logs/D30/
```

根据实际执行情况保存：

- 工程扫描日志；
- Maven 编译日志；
- 单元测试日志；
- 集成测试日志；
- 数据库连接检查日志；
- PostgreSQL/pgvector 验证日志。

日志要求：

1. 保留真实执行输出。
2. 失败日志不得删除或伪造。
3. 不得记录数据库密码、API Key 等敏感信息。
4. 不要把无关历史日志复制到 D30。
5. 区分本地生成日志、已归档证据和已经提交 Git 的日志。
6. 不得仅因为日志目录为空，就认定相关测试从未执行；应检查证据目录和实际执行记录。

---

## 十、Git 与版本管理

执行前检查：

```bash
git status --short

git branch --show-current

git rev-parse HEAD
```

要求：

- 保留用户现有未提交修改。
- 不得擅自切换分支。
- 不得重置、清理或覆盖用户工作区。
- 不得执行 `git clean`。
- 不得执行破坏性回滚。
- 不得自动提交 Git。
- 完成后提供修改文件清单。
- 如果用户要求提交，再根据实际变更生成提交建议。

提交前检查：

- 是否意外包含密钥；
- 是否提交了本地运行配置；
- 是否提交了无关日志；
- 是否提交了临时文件；
- 是否修改了不属于 D30 的历史工程。

---

## 十一、AI 编码助手执行流程

按照以下阶段顺序实施，不得跳过关键检查。

### 阶段 1：工程扫描

交付：

- 当前 Git 状态；
- D30 工程结构；
- POM 和依赖版本；
- 已有向量存储能力；
- D29 Embedding 实现；
- 相关配置和测试；
- 当前已知问题。

此阶段只读，不修改业务代码。

### 阶段 2：技术方案设计

交付：

- 向量数据库选型结论；
- PostgreSQL/pgvector 接入方案；
- Embedding 维度决策；
- 向量空间兼容性方案；
- 需要修改和新增的文件清单；
- 测试和验收方案。

如存在维度不明确、数据风险或环境冲突，必须先定位，不得猜测。

### 阶段 3：环境验证

交付：

- Docker 环境检查；
- PostgreSQL 健康检查；
- pgvector 扩展版本；
- 数据库连接；
- 向量类型和运算验证。

如果环境不可用，记录阻塞原因，停止依赖数据库的后续验证，不得伪造通过结果。

### 阶段 4：工程实现

交付：

- 必要 Maven 依赖；
- 必要配置；
- PostgreSQL 数据访问集成；
- pgvector 向量存储实现；
- 维度校验；
- 必要的异常处理；
- 对应测试。

严格执行最小必要改动。

### 阶段 5：编译与测试

执行：

- Maven 编译；
- 相关单元测试；
- 必要集成测试；
- 数据库连接验证；
- 向量写入验证；
- 相似度查询验证。

记录真实结果和失败原因。

### 阶段 6：文档与证据

交付：

- 两份完整技术文档；
- D30 证据目录；
- D30 日志；
- 修改文件清单；
- 验收结果；
- 未完成事项。

### 阶段 7：最终总结

最终回复必须明确：

1. 本轮完成了什么。
2. 哪些文件发生修改。
3. PostgreSQL 是否真实启动并健康。
4. pgvector 扩展是否验证通过。
5. 实际 Embedding 维度是多少。
6. 向量写入是否通过。
7. 相似度查询是否通过。
8. Maven 编译是否通过。
9. 测试通过和失败数量。
10. 两份文档的实际路径。
11. 证据和日志路径。
12. 当前遗留问题。

---

## 十二、禁止事项

以下行为严格禁止：

1. 未扫描 POM 就直接新增依赖。
2. 未检查历史向量方案就重新设计。
3. 将 PostgreSQL 当作现有 MySQL 的替代品。
4. 引入第二套向量数据库。
5. 擅自修改 Docker 端口映射和数据卷。
6. 删除历史容器、镜像或数据库数据。
7. 将数据库密码写入代码或提交 Git。
8. 未确认 Embedding 输出维度就创建正式向量表。
9. 仅修改数据库维度，不同步模型和校验逻辑。
10. 混合不同向量空间的数据。
11. 数据库启动失败后继续宣称检索成功。
12. 用 Mock 结果冒充 PostgreSQL 实际检索结果。
13. 用单元测试代替必要的数据库集成测试。
14. 虚构编译、测试、数据库运行或检索结果。
15. 无关重构、过度设计或提前开发后续 Day 功能。
16. 自动删除或覆盖 `docs/temp_docs/` 文件。
17. 自动清理 Docker 资源或执行破坏性 Git 操作。

---

## 十三、最终交付清单

| 类别     | 交付内容                        | 必须完成 |
| -------- | ------------------------------- | -------- |
| 工程     | PostgreSQL/pgvector 必要集成    | 是       |
| 环境     | Docker Compose 与数据库健康验证 | 是       |
| 数据库   | pgvector 扩展验证               | 是       |
| 向量     | Embedding 实际维度核验          | 是       |
| 向量     | 写入和相似度查询验证            | 是       |
| 测试     | 编译及相关测试                  | 是       |
| 文档     | 项目迭代全量说明                | 是       |
| 文档     | 技术说明与操作实践指南          | 是       |
| 证据     | D30 验收证据                    | 是       |
| 日志     | D30 执行日志                    | 是       |
| 临时文件 | 统一放入 temp_docs              | 是       |
| Git      | 修改文件与工作区状态说明        | 是       |

## 十四、最终执行原则

本轮最重要的目标是：

**在不破坏现有 M3-Java 工程和 PostgreSQL 环境的前提下，建立一套真实可运行、维度一致、可验证、可追溯的 PostgreSQL + pgvector 向量存储基础能力。**

必须坚持：

- 先扫描，再决策；
- 先核验环境，再接入代码；
- 先确认维度，再创建正式向量表；
- 先完成真实写入，再验证真实检索；
- 先获得证据，再汇报结果；
- 优先最小改动，避免无关扩展；
- 不确定的内容明确标记，不得自行编造。

最终结果必须以实际仓库状态、真实执行记录和可复现证据为准。