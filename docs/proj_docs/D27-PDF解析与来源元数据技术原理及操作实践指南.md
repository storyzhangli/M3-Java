# D27-PDF 解析与来源元数据技术原理及操作实践指南

> 适用工程：M3-Java / D27 / D27_PDF_WORD（com.zl.d27）
> 技术基线：JDK21、Spring Boot 3.5.16、Spring AI 1.1.8、Apache PDFBox 3.0.7、MyBatis-Plus 3.5.17
> 文档定位：讲清「底层原理 + 来源元数据 + 可落地操作步骤 + 故障排查」，与《功能实现全量说明》互补。

---

## 1. 是什么：PDF 解析与来源元数据

PDF（Portable Document Format，便携式文档格式）是一种与设备、分辨率无关的固定版式格式。PDF 内部不是「连续文本流」，而是由页面对象、内容流（绘制指令）、字体、坐标等组成。所谓「解析出文本」，是把内容流中定位文本的绘制指令（如 `Tj`、`TJ` 算子）结合字体映射还原为字符，并按坐标组织阅读顺序。

来源元数据（Source Metadata）指：从一段提取文本，能反查它来自哪个文档（documentId）、什么来源（source）、哪个版本（version）、哪一物理页（pageNumber），并保留更新时间（updatedAt）。D27 用它建立「原始文档 → 解析结果」的可追溯链路。

---

## 2. 为什么选 PDFBox 3.0.7

| 候选 | 结论 | 依据 |
| --- | --- | --- |
| 直接 PDFBox | 采用 | 已在 classpath（spring-ai-pdf-document-reader 传递），零新增依赖；可精确控制页面边界与页码 |
| Spring AI PdfReader | 不强制接线 | 其底层仍用 PDFBox，封装后对逐页边界与 blank 控制不如直接调用直观 |
| Tika | 不用于 PDF 主链路 | 自动探测强，但页面边界控制粒度弱于直接 PDFBox |

版本敏感事实（来自真实 dependency:tree 与源码）：

- PDFBox 3.x 加载入口是 `org.apache.pdfbox.Loader.loadPDF(byte[])`；2.x 的静态 `PDDocument.load()` 已不存在。
- 提取器 `org.apache.pdfbox.text.PDFTextStripper` 的页码参数为 **1-based**。
- `PDDocument` 是 `Closeable`，必须 try-with-resources，否则文件句柄与字体资源泄漏。

---

## 3. 底层原理

### 3.1 PDF 加载与页面计数

```java
try (PDDocument document = Loader.loadPDF(bytes)) {
    int pageCount = document.getNumberOfPages();
    ...
}
```

- `Loader.loadPDF` 解析文件头、交叉引用表（xref）与对象树；文件损坏、xref 错乱、魔数非法时抛 IOException（PDFBox 内部还会尝试修复，修复失败才抛出）。
- `getNumberOfPages()` 返回物理页数量，即页面树（Page Tree）的叶子数。

### 3.2 文本提取与页面边界

`PDFTextStripper` 遍历某页内容流，按字体编码把文字算子还原为字符，再依据坐标（`setSortByPosition(true)`）排序输出。

```java
PDFTextStripper stripper = new PDFTextStripper();
stripper.setStartPage(page);
stripper.setEndPage(page);
stripper.setSortByPosition(true);
String text = stripper.getText(document);
```

- 同一页 start=end，提取范围被严格限定在该物理页，**不会把相邻页拼接进来**，这是页面边界的保证。
- 循环从 1 到 pageCount，物理页码由循环序号产生，不依赖 PDF 内可能缺失的页码标签，因此「页码丢失」不影响定位。

### 3.3 空白页与扫描件识别

```java
boolean hasUsable = text.chars().anyMatch(Character::isLetterOrDigit);
```

- `Character.isLetterOrDigit` 对中英文、数字、表意文字均成立；只含空白/换行/标点的页判为 blank。
- 单页空白：保留该页行（blank=1），文档仍可 COMPLETED。
- 全部页空白：典型为扫描图像型 PDF（文字以位图存在、无文本层），落 FAILED/NO_EXTRACTABLE_TEXT，明确需要 OCR，但页面行仍保留以维持定位。

### 3.4 原始对象与解析对象分离原理

- 原始 PDF 字节只用于计算 hash 与解析，不入库。
- 文档级：`knowledge_document` 存元数据、状态、hash。
- 页面级：`knowledge_document_page` 存每物理页文本与来源字段。
- 页面通过 document_id 外键式关联（逻辑外键，未强制物理约束），形成一对多。

---

## 4. 来源元数据字段与追溯链路

| 字段 | 含义 | 追溯作用 |
| --- | --- | --- |
| documentId | 文档业务标识 | 反查原始文档与文档级元数据 |
| source | 文档来源 | 区分来源系统（如 manuals.plus、本地上传） |
| title | 文档标题 | 人工识别与归档 |
| version | 文档版本 | 区分不同版本，避免跨版本混淆 |
| pageNumber | 物理页序号（1 起） | 精确定位到物理页 |
| content | 该页提取文本 | 解析结果本体 |
| updatedAt | 更新时间 | 判断解析新鲜度、重解析时间点 |

追溯方向双向闭合：

- 正向：原始文档 → 页面列表（pages 端点）→ 单页。
- 反向：任一页视图都带 documentId，可回到文档详情。

---

## 5. 操作实践

### 5.1 准备数据库（ai_db，MySQL 8.x）

按顺序执行（脚本均为 `CREATE TABLE IF NOT EXISTS`，可安全重复执行，不覆盖数据）：

```text
D27_PDF_WORD/src/main/resources/db/schema-knowledge-document.sql
D27_PDF_WORD/src/main/resources/db/schema-document-page.sql
```

回滚（仅在确认需要时）：

```sql
DROP TABLE IF EXISTS knowledge_document_page;
DROP TABLE IF EXISTS knowledge_document;
```

### 5.2 配置

`application.yml` 关键项：

```yaml
document:
  import:
    max-file-size-bytes: 2097152
    charset: UTF-8
    title-max-length: 200
    allowed-extensions: [txt, md, pdf]
    allowed-content-types: [text/plain, text/markdown, application/pdf]
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB
```

注意：业务 2MB 先于容器 10MB 生效；超过 2MB 由业务校验拒绝，超过 10MB 由容器在解析阶段拒绝。

### 5.3 启动与构建

```powershell
$env:JAVA_HOME="D:\SoftwareInstall\JDK\JDK21"
& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -f D27\pom.xml clean test
```

### 5.4 调用示例（curl）

导入 PDF（新建文档）：

```bash
curl -X POST "http://localhost:8080/api/v1/documents/import" \
  -F "file=@D27-佳能用户手册-DB-测试素材.pdf;type=application/pdf" \
  -F "source=manuals.plus" \
  -F "title=Canon MP500 Manual"
```

关联已登记文档导入：

```bash
curl -X POST "http://localhost:8080/api/v1/documents/import" \
  -F "file=@manual.pdf;type=application/pdf" \
  -F "documentId=<已登记的documentId>"
```

来源定位查询：

```bash
curl "http://localhost:8080/api/v1/documents/<documentId>/pages"
curl "http://localhost:8080/api/v1/documents/<documentId>/pages/1"
```

响应（统一 Result，HTTP 200）：

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "documentId": "....",
      "source": "manuals.plus",
      "title": "Canon MP500 Manual",
      "version": 1,
      "pageNumber": 1,
      "content": "....",
      "contentLength": 1520,
      "blank": false,
      "updatedAt": "...."
    }
  ]
}
```

### 5.5 真实 PDF 验证复现

本轮验证程序位于 `docs/temp_docs/D27/RealPdfVerify.java`，直接调用生产组件并把 UTF-8 结果写入文件，规避控制台中文乱码：

```text
输入：docs/proj_docs/D27-佳能用户手册-DB-测试素材.pdf
输出：docs/evidence/D27/D27-real-pdf-verify.md
实测：11 页、0 空白、9304 字符、904ms
```

---

## 6. 状态机与错误码

| 状态 | 含义 |
| --- | --- |
| PENDING | 已登记未导入 |
| PROCESSING | 解析中 |
| COMPLETED | 解析成功（可含部分空白页） |
| FAILED | 解析失败或无可用文本 |

自有错误码（非通用 ResultCode）：

| 错误码 | 触发条件 | 处理建议 |
| --- | --- | --- |
| PARSE_FAILED | 损坏/非法 PDF、解析异常 | 检查文件完整性与格式 |
| NO_EXTRACTABLE_TEXT | 全部页面无文本层（扫描/空） | 接入 OCR 后再处理 |
| PROCESSING_FAILED | 未预期运行时异常 | 查日志、稍后重试 |

重试规则：hash 命中且状态为 FAILED 时允许同内容重新导入，复用同一文档行；重新解析在同事务内先删旧页面行再插新行。

---

## 7. 故障排查（Pitfalls）

| 现象 | 根因 | 排查/解决 |
| --- | --- | --- |
| 编译报找不到 PDDocument.load() | 误用 2.x API | 改用 `Loader.loadPDF(byte[])` |
| PDF 含非 UTF-8 字节被拒 | 对二进制做了文本编码检查 | PDF 分支跳过 UTF-8 解码（已实现） |
| 解析返回 FAILED/NO_EXTRACTABLE_TEXT | 扫描件无文本层 | 属预期，需 OCR，不应改判成功 |
| 页面文本串页 | 未限定单页起止 | 每页 start=end（已实现） |
| 控制台中文乱码 | 原生进程编码问题 | 结果写 UTF-8 文件，不依赖控制台 |
| 上传被拒「上限」 | 超 2MB 业务上限 | 核对大小，或经评估调整配置 |
| Mockito any() 编译歧义 | BaseMapper 重载 | 用具类型 `any(DocumentPageContentPO.class)` |

修复停止线：同一根因连续两次修复未解决，停止试错并输出诊断记录（现象/复现/根因假设/已尝试/失败证据/下一步）。

---

## 8. Word 可选扩展（未接线）

- Apache POI 5.5.1 已随 Tika 在 classpath（poi-ooxml 等），具备 .doc/.docx 能力。
- 建议扩展方式：定义统一解析接口（如 `DocumentParser`），新增 `WordDocumentParser` 用 POI 的 `XWPFDocument` 提取段落，按物理段落/分页映射来源，再在导入服务按扩展名路由。
- 本轮不强制接线，避免扩大范围；具体实现留待后续迭代。

---

## 9. 边界与安全注意

1. 不实现 OCR、Embedding、VectorStore、Chunk 智能切分、向量检索、RAG 问答、Agent/Tool/MCP。
2. 页面级文本不是 Chunk，禁止在文档中混用两个概念。
3. 当前无鉴权，X-Dev-User 仅归属标识；生产化需补认证授权与输入审计。
4. 错误信息只回安全摘要，不回堆栈、不回密钥；API Key 必须经环境变量/程序参数注入，禁止硬编码。
5. 未做 git commit/push；临时文件与日志保留，不自动删除。

---

## 10. 记忆简语与层级树

记忆简语：

- **PDF 是绘制指令不是文本流，PDFTextStripper 靠字体+坐标还原字符。**
- **Loader.loadPDF 是 3.x 入口，页码 1 起、逐页起止保边界。**
- **来源定位七字段：documentId/source/title/version/pageNumber/content/updatedAt。**
- **扫描件无文本层 → NO_EXTRACTABLE_TEXT，要 OCR；Page 不是 Chunk。**

层级树：

```text
来源元数据
├─ 文档级 knowledge_document
│  ├─ documentId / source / title / version
│  ├─ status / contentHash / contentLength
│  └─ errorCode / errorMessage
└─ 页面级 knowledge_document_page
   ├─ pageNumber（1 起物理页）
   ├─ content / contentLength
   ├─ blank（空白标记）
   └─ createdAt / updatedAt
```
