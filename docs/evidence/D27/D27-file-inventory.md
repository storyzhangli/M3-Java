# D27 文件清单（PDF Word 解析与来源元数据）

> 路径均相对 `M3-Java`。仅列本轮新增/修改文件与交付目录，不包含 D26 既有未改动文件。
> 行数为真实统计（Get-Content Measure-Object -Line）。

## 1. 主源码：新增（src/main）

| 路径（相对 D27_PDF_WORD/src） | 行数 | 职责 |
| --- | --- | --- |
| main/resources/db/schema-document-page.sql | 28 | 页面级结果表 knowledge_document_page DDL |
| main/java/com/zl/d27/document/model/DocumentPageContent.java | 37 | 页面级提取内容领域模型 |
| main/java/com/zl/d27/document/po/DocumentPageContentPO.java | 29 | 页面表持久化对象 |
| main/java/com/zl/d27/document/mapper/DocumentPageContentMapper.java | 12 | 页面 Mapper（BaseMapper，无 XML） |
| main/java/com/zl/d27/document/dto/DocumentPageView.java | 20 | 页面对外视图 |
| main/java/com/zl/d27/document/support/PdfDocumentParser.java | 61 | PDFBox 3.0.7 逐页解析器 |
| main/java/com/zl/d27/document/service/DocumentPageService.java | 55 | 页面替换写入与来源定位查询 |

## 2. 主源码：修改（src/main）

| 路径（相对 D27_PDF_WORD/src） | 行数 | 变更点 |
| --- | --- | --- |
| main/java/com/zl/d27/document/config/DocumentImportProperties.java | 26 | 默认白名单加 pdf / application/pdf |
| main/java/com/zl/d27/document/support/FileUploadValidator.java | 103 | validateType 返回扩展名；PDF 跳过 UTF-8 解码 |
| main/java/com/zl/d27/document/support/DocumentConverter.java | 110 | 新增 toPageDomain/toPagePO/toPageView |
| main/java/com/zl/d27/document/service/DocumentImportService.java | 206 | 类型路由、FAILED 重试、handlePdf/handleText |
| main/java/com/zl/d27/document/controller/DocumentController.java | 72 | 新增两个 pages 端点 |
| main/resources/application.yml | 169 | allowed 列表加 pdf、application/pdf |

## 3. 测试（src/test）

| 路径（相对 D27_PDF_WORD/src） | 行数 | 说明 |
| --- | --- | --- |
| test/java/com/zl/d27/document/DocumentImportServiceTest.java | 223 | 9 测试（含 PDF 路由、全空白、FAILED 重试） |
| test/java/com/zl/d27/document/DocumentControllerTest.java | 125 | 5 测试（含 pages 端点） |
| test/java/com/zl/d27/document/FileUploadValidatorTest.java | 99 | 9 测试（新增 PDF 二进制放行） |
| test/java/com/zl/d27/document/PdfDocumentParserTest.java | 145 | 6 测试（真实 PDF，含中英文混合） |
| test/java/com/zl/d27/document/DocumentPageServiceTest.java | 70 | 4 测试（先删后插、查询） |

## 4. 未改动（明确边界）

- 现有三表（chat_session/chat_message/prompt_template）与对应代码。
- POM 依赖：无新增依赖（PDFBox/POI 已在 classpath）。
- D26 既有 knowledge_document DDL（schema-knowledge-document.sql）保持原样。
- 未实现 OCR、Embedding、VectorStore、Chunk 智能切分、向量检索、RAG 问答、Agent/Tool/MCP。

## 5. 交付与证据目录

| 目录（相对 M3-Java） | 内容 |
| --- | --- |
| docs/proj_docs | 任务书、两份正式文档、真实 PDF 素材 |
| docs/evidence/D27 | 设计结论、文件清单、测试摘要、迭代状态、真实 PDF 验证记录、surefire-reports |
| docs/temp_docs/D27 | 基线、classpath、真实验证程序（不自动删除） |
| logs/D27 | dir-tree.log、01-compile.log、02-test.log |

## 6. 构建口径

- 主源文件编译：131 个（mvn clean compile BUILD SUCCESS）。
- 全量测试：168 个测试，0 失败、0 错误、0 跳过（mvn clean test BUILD SUCCESS）。
