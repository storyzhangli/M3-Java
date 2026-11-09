# D27 技术选型与方案设计结论

## 1. 实际技术版本（来自 pom 与 dependency:tree）
- JDK21、Spring Boot 3.5.16、Spring AI 1.1.8、Maven 3.9.16
- 已存在解析能力：spring-ai-pdf-document-reader 1.1.8 → **Apache PDFBox 3.0.7**
- spring-ai-tika-document-reader 1.1.8 → Tika 3.3.1，microsoft 模块含 **Apache POI 5.5.1**

## 2. PDF 解析器选择与依据
- 选择直接使用 **PDFBox 3.0.7**：它已在 classpath（经 spring-ai-pdf-document-reader 传递），无需新增依赖，且能精确控制页面边界与物理页码。
- 关键兼容性事实：PDFBox 3.x 使用 `org.apache.pdfbox.Loader.loadPDF(byte[])`，**不是** 2.x 的 `PDDocument.load()`；文本提取用 `org.apache.pdfbox.text.PDFTextStripper`，setStartPage/setEndPage 为 1-based。
- 不新增依赖、不升级 Spring Boot，避免重复解析库。

## 3. 页面级结果结构（与原始 PDF 分离，最小新增）
- 领域模型 DocumentPageContent：documentId、source、title、version、pageNumber、content、contentLength、blank、createdAt、updatedAt。
- PO DocumentPageContentPO + Mapper + 表 knowledge_document_page，唯一约束 (document_id, page_number)。
- 原始字节与提取文本是不同对象：knowledge_document 存元数据，knowledge_document_page 存页面文本。

## 4. 页面与来源关联
- documentId 关联 knowledge_document；pageNumber 从 1 开始的物理页序号，与印刷页码明确区分。
- 每个物理页（含空白页）都保留一行，blank 标记空白页，避免页面定位丢失。
- 重新解析时按 documentId 先删旧页面行再插入新行（同事务）。

## 5. 类型路由与校验调整
- 校验白名单扩展 pdf/application/pdf；PDF 为二进制，跳过仅用于文本的 UTF-8 严格解码检查。
- 导入服务按扩展名路由：pdf → PdfDocumentParser；txt/md → BasicDocumentParser。

## 6. 异常与状态
- 加载/解析异常（IOException、损坏 PDF）在解析器内包装为 DocumentParseException → 服务落 FAILED(PARSE_FAILED)。
- 0 个有效页面 → DocumentParseException。
- 全部页面均无可用文字（扫描型/空文本）→ FAILED，错误码 NO_EXTRACTABLE_TEXT，提示需 OCR；页面行仍保留。
- 未预期运行时 → FAILED(PROCESSING_FAILED)。
- FAILED 文档允许同内容重新导入重试（hash 命中 FAILED 时不按重复短路）。

## 7. Word 扩展
- POI 5.5.1 已随 Tika 在 classpath，具备 Word 能力；本轮只输出统一解析接口的扩展设计与验证结论，不强制接线，避免扩大范围。

## 8. 真实抽查方案
- 用真实佳能手册（1.44MB，%PDF，低于 2MB 业务上限）走完整解析，记录总页数、首页/正文页/空白页文本与中英文数字，对照原文。
- 测试生成的确定性 PDF 用于单测；真实手册证据单独归档。

## 9. 边界
不实现 OCR、Embedding、VectorStore、Chunk 智能切分、向量检索、RAG 问答、Agent/Tool/MCP；页面级文本不等于 RAG Chunk。
