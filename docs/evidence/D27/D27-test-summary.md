# D27 测试摘要（真实执行结果）

## 1. 总体结果

- 命令：`mvn -f D27/pom.xml clean test`（JDK21，Maven 3.9.16）
- 结果：**Tests run: 168, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
- 报告原件：`D27_PDF_WORD/target/surefire-reports`；副本：`docs/evidence/D27/surefire-reports`（59 个文件）。
- 测试日志：`logs/D27/02-test.log`。

## 2. document 包测试明细（真实 surefire 计数）

| 测试类 | 测试数 | 结果 |
| --- | --- | --- |
| BasicDocumentParserTest | 3 | 全通过 |
| DocumentControllerTest | 5 | 全通过 |
| DocumentImportServiceTest | 9 | 全通过 |
| DocumentPageServiceTest | 4 | 全通过 |
| DocumentRegistrationServiceTest | 5 | 全通过 |
| FileUploadValidatorTest | 9 | 全通过 |
| PdfDocumentParserTest | 6 | 全通过 |
| Sha256ContentHasherTest | 4 | 全通过 |
| 小计 | 45 | 全通过 |

其余 123 个测试为既有模块（会话、SSE、观测、韧性、适配器等）回归，全部通过。

## 3. 任务书 11 个测试场景对照

| # | 场景 | 对应测试（真实执行） | 结果 |
| --- | --- | --- | --- |
| 1 | 正常 PDF | DocumentImportServiceTest.pdfImportCompletesAndPersistsPages | 通过 |
| 2 | 多页 PDF | PdfDocumentParserTest.multiPagePdfExtractsWithPageBoundaries | 通过 |
| 3 | 空 PDF/无有效页面 | PdfDocumentParserTest.emptyBytesThrowsParseException；解析器 pageCount<=0 分支 | 通过 |
| 4 | 空白页面 | PdfDocumentParserTest.blankPageKeptWithPosition | 通过 |
| 5 | 损坏 PDF | PdfDocumentParserTest.corruptedPdfThrowsParseException | 通过 |
| 6 | 不支持格式 | FileUploadValidatorTest.unsupportedExtensionRejected | 通过 |
| 7 | 非法文件内容 | FileUploadValidatorTest.illegalEncodingRejected；plainTextMisroutedToPdfParserFails | 通过 |
| 8 | 页码丢失 | multiPagePdfExtractsWithPageBoundaries（页码由循环序号生成，不依赖 PDF 元数据，断言 1..N 连续） | 通过 |
| 9 | 中英文混合 | PdfDocumentParserTest.mixedChineseEnglishDigitsExtracted（真实嵌入 simhei.ttf） | 通过 |
| 10 | 扫描型 PDF | DocumentImportServiceTest.pdfAllBlankPagesMarksNoExtractableText | 通过 |
| 11 | 解析失败 | parseFailureMarksFailed、unexpectedRuntimeMarksFailed | 通过 |

## 4. 真实 PDF 解析（非 Mock）

- 素材：`docs/proj_docs/D27-佳能用户手册-DB-测试素材.pdf`，1,509,800 字节，魔数 `%PDF-`。
- 方式：独立 Java 程序直接调用生产组件 `PdfDocumentParser.parse`，结果落 `docs/evidence/D27/D27-real-pdf-verify.md`。
- 实测：物理页 11、空白页 0、提取文本 9,304 字符、英文字母 7,177、数字 157、耗时 904 ms。
- 诚实说明：该素材实际为**纯英文**手册（中日韩表意文字 0），中文/混合能力由第 3 节第 9 项真实字体测试验证。

## 5. 未执行项（不虚构）

- 真实 MySQL 端到端 HTTP 上传：未执行，需先在 `ai_db` 执行 `schema-knowledge-document.sql` 与 `schema-document-page.sql`。
- 当前无鉴权，`X-Dev-User` 仅归属标识，不假设权限已强制。
- 真实模型 remote 无 `AI_API_KEY` 未验证，与本任务无关。
