# D28 文件清单与改动范围

> 基目录：`D28\D28_TCASS\src`。本迭代新增 23 个 chunk 主源文件、6 个测试文件，
> 并最小改动 3 个既有文件；未新增任何依赖，未改动无关模块。

## 一、新增主源文件（23 个，包 `com.zl.d28.document.chunk`）

| 分层 | 文件 | 职责 |
|---|---|---|
| clean | `clean\TextCleaner.java` | 逐页文本清洗、空白归一、结构块识别 |
| config | `config\ChunkProperties.java` | `app.ai.chunk` 配置绑定、默认值与校验 |
| support | `support\ChunkBoundarySupport.java` | 边界切点选择、代理对保护、来源解析 |
| split | `split\TextSplitter.java` | 切分器统一接口 |
| split | `split\FixedSizeSplitter.java` | 固定长度滑窗切分（硬游标） |
| split | `split\StructureSplitter.java` | 标题/段落结构切分 |
| service | `service\DocumentChunkService.java` | 页面→清洗→两策略→对比编排 |
| model | `model\BlockType.java` | 结构块类型枚举 HEADING/PARAGRAPH/LIST_ITEM |
| model | `model\ChunkStrategy.java` | 策略枚举 FIXED_SIZE/STRUCTURE |
| model | `model\TextBlock.java` | 结构块（含全局偏移） |
| model | `model\ChunkSource.java` | 单来源（页码/段落可空） |
| model | `model\Chunk.java` | Chunk 主体与定位聚合方法 |
| model | `model\CleanedPage.java` | 单页清洗摘要 |
| model | `model\CleanedDocument.java` | 清洗结果承载 |
| model | `model\ChunkStats.java` | 单策略统计 |
| model | `model\ChunkComparison.java` | 两策略对比 |
| model | `model\ChunkResult.java` | 完整处理结果 |
| model | `model\ChunkContext.java` | 文档上下文 record |
| dto | `dto\ChunkSourceView.java` | 来源视图 |
| dto | `dto\ChunkView.java` | Chunk 视图 |
| dto | `dto\ChunkStatsView.java` | 统计视图 |
| dto | `dto\ChunkComparisonView.java` | 对比视图 |
| dto | `dto\ChunkViewMapper.java` | 领域对象到视图映射 |

## 二、新增测试文件（6 个）

| 文件 | 覆盖内容 |
|---|---|
| `ChunkTestSamples.java` | 真实样本工厂（页面/属性/重复词/超长串） |
| `ChunkPropertiesTest.java` | 10 项配置校验 |
| `clean\TextCleanerTest.java` | 16 项清洗规则 |
| `split\FixedSizeSplitterTest.java` | 11 项固定切分 |
| `split\StructureSplitterTest.java` | 8 项结构切分 |
| `service\DocumentChunkServiceTest.java` | 5 项端到端编排 |

## 三、最小改动的既有文件（3 个）

| 文件 | 改动 |
|---|---|
| `document\controller\DocumentController.java` | 注入 chunk 服务，新增两个 Chunk 端点 |
| `resources\application.yml` | 新增 `app.ai.chunk` 配置段 |
| `document\DocumentControllerTest.java` | 构造适配 6 参，新增 Chunk 端点切片测试 |

## 四、明确未改动

- 未改动 PDF 解析（D27 的 `PdfDocumentParser`）、持久化、对话、安全等无关模块。
- 未新增/升级依赖，未改 POM，未改数据库表结构与公共接口契约。
