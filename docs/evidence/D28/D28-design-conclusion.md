# D28 设计结论：文本清洗与可配置 Chunk 切分

> 依据：D28 实际源码（`com.zl.d28.document`，137 个主源文件）、D27 已交付的 PDFBox 3.0.7 逐页解析与 `DocumentPageContent` 页面模型、D28 任务书。
> 结论范围：仅设计，不含已实现状态；实现状态以编码与真实构建记录为准。

## 1. 现有解析结果结构（复用点）

- `PdfDocumentParser` 已用 `Loader.loadPDF(byte[])` 逐物理页提取，产出 `List<DocumentPageContent>`，字段含 `documentId/source/title/version/pageNumber/content/contentLength/blank`。
- 页面文本是**物理页提取结果，不是 RAG Chunk**。D28 不重写解析，直接以页面列表作为清洗输入。
- 已确认工程内**不存在**任何 Chunk/Splitter/Tokenizer 实现（关键词检索 0 命中）。
- 启动类 `D28SpringAiApplication` 带 `@ConfigurationPropertiesScan`，新配置类可直接被扫描。

## 2. 长度计算单位（关键口径）

- 采用 **UTF-16 code unit 数量**（即 Java `String.length()`、`substring` 的原生单位），不引入 Tokenizer。
- 切窗与 `chunkSize/overlap` 全部基于该单位，保证确定性、零外部依赖。
- 边界保护：切分点绝不落在代理对（surrogate pair）中间；统计中同时输出 Unicode 码点数（code point）作对照。
- **明确不把字符数宣称为 Token 数**；文档说明字符数与真实 Token 的差异（无分词器、不同分词器结果不同、CJK 与空格切分语言差异）。

## 3. 文本清洗方案（独立组件 TextCleaner）

- 按页清洗，保留 `pageNumber` 关联，不跨页混洗。
- 规则：
  1. 统一换行：`\r\n`、`\r` → `\n`；行/段分隔符（U+2028/2029）→ 换行。
  2. 逐行处理：去首尾空白；行内水平空白（空格/Tab/NBSP U+00A0/全角空格 U+3000 等）连续序列归一为单个普通空格。
  3. 空行识别：无有效字符的行视为空行；连续空行折叠为至多一个段落分隔，**不删除所有换行**。
  4. 重复换行：3+ 换行折叠为 2 个（段落边界），保留标题/列表/段落的单换行结构。
  5. 不删除有意义标点、数字、单位、术语。
- 产出 `CleanedDocument`：拼接后的规范文本、`TextBlock` 列表（含全局偏移与来源）、每页清洗前后长度。
- 禁令落实：不全局压成一个空格（保留换行与结构）、不删所有换行、不破坏标题/列表、不丢页码定位。

## 4. 结构单元 TextBlock

- 字段：`paragraphIndex`（全局，从 0 起）、`pageNumber`、`type`（HEADING/PARAGRAPH/LIST_ITEM）、`text`、`length`、全局 `globalStart/globalEnd`。
- 标题识别（启发式，文档标注局限）：Markdown `#`；编号/章节（`1.`、`1.2`、`Chapter`、`APPENDIX`）；短行且无句末标点、全大写/Title Case。
- 降级：识别不到标题时，按段落与页面边界组织，不假设 PDF 都能提取标题层级。

## 5. 固定长度切分（FixedSizeSplitter）

- 窗口：`step = chunkSize - overlap`；硬窗 `[start, start+chunkSize)`。
- 语义边界保护：在硬末端向前的容差窗口内，优先取最近换行、再取最近空格作为切点；找不到（超长无断点串）才硬切，保证不超上限；不切代理对。
- Overlap：下一窗起点 `end - overlap`；overlap=0 无重叠；实际重叠由全局偏移复核。
- 末尾：尾部不足 `minChunkSize` 且非唯一块时并入上一块（不产生空/碎 Chunk）；不产生空 Chunk。
- 来源：用全局偏移映射到覆盖的所有 block，聚合页码/段落，跨页跨段全部保留。

## 6. 标题/段落切分（StructureSplitter）

- 顺序累积 block，标题开启新段且标题与正文同块；加入下一 block 会超 `maxChunkSize` 时封块。
- 短段落合并：连续短 block 在 max 限制内合并；极短最后一段保留（能并入则并入，否则独立成块，不丢弃）。
- 超长段落：单 block 超 `maxChunkSize` 时，按边界保护二次切分（内部不重叠，避免段落内重复），来源沿用该 block 的页/段。
- 尽量避免句中截断；无结构时降级为段落+页面边界。

## 7. 配置（ChunkProperties，前缀 app.ai.chunk，配置与算法分离）

| 参数 | 默认 | 范围/校验 |
| --- | --- | --- |
| chunkSize | 500 | 1..100000 |
| chunkOverlap | 100 | 0.. 且必须 < chunkSize |
| minChunkSize | 50 | 1..chunkSize |
| maxChunkSize | 1000 | >= chunkSize |
| boundaryTolerance | 100 | 0..chunkSize |
| mergeShortBlocks | true | 布尔 |
| lengthUnit | UTF16_CODE_UNIT | 固定口径 |

- `validate()` 显式校验：overlap≥size 拒绝、min>max 拒绝、min>size 拒绝等；`@PostConstruct` 启动快速失败。

## 8. Chunk 元数据与来源（Chunk + ChunkSource）

- Chunk 必备：`documentId/title/version/chunkIndex/content`，另含 `strategy/length/codePointCount/globalStart/globalEnd/sources`。
- ChunkSource：`pageNumber`（可空）、`paragraphIndex`（可空）、`globalStart/globalEnd`；Chunk 提供聚合 `pageNumbers/firstPage/lastPage/paragraphIndices` 与定位能力标志。
- 编号规则：`chunkIndex` 从 0 起、按发射顺序，同文档同版本同策略稳定有序；身份命名空间 = documentId+version+strategy+chunkIndex，跨文档不混淆，版本变化可区分。
- 跨页/跨段：来源为列表，不丢弃非首个来源；无可靠页码/段落时字段为空并标注定位能力边界。

## 9. 对比与统计（ChunkStats / ChunkComparison）

- 同一份清洗文本跑两策略；指标：数量、最小/平均/最大长度、长度分布桶、空 Chunk 数、相邻实际重叠、覆盖（无丢失/重复字符数）、跨页块数、有页码/段落定位比例、句中截断代理计数。
- 语义完整度：人工抽查 + 明确检查标准（标题不与正文分离、段落非句中截断、列表项完整），并以自动化断言（无空块、覆盖无丢失）佐证；不引入大模型评分。
- 数据全部来自真实运行，相同输入保证公平。

## 10. 测试样本与边界

- 真实样本：D27 佳能手册（11 页、9304 字符）真实跑两策略；另用真实文本样本覆盖任务书 16 个边界场景（空/空白/极短/连续短/超长/超长单句/中文/中英混合/特殊字符/overlap=0/>0/≥size 拒绝/多页/跨页/多版本/极短末段）。
- 断言：数量、长度边界、overlap、来源元数据、无丢失/重复；算法用真实对象，不靠 Mock 证明。

## 11. 边界排除

- 不实现 Embedding、VectorStore、向量索引、RAG 检索/问答、Agent、Tool Calling、MCP；不做无关重构；不新增依赖；切分组件不与 Embedding 强耦合、可独立替换。
