# D26 实现文件清单（Implementation File Inventory）

> 路径相对模块根 `D26/D26_DIABP`。本轮为纯新增 + 两处既有文件的最小增量，未删除任何文件。

## 1. 新增主源码（13 个）

| 路径 | 职责 |
|---|---|
| src/main/resources/db/schema-knowledge-document.sql | knowledge_document 表 DDL（含 content_hash 唯一约束） |
| src/main/java/com/zl/d26/document/config/DocumentImportProperties.java | app.ai.document.* 服务端导入配置 |
| src/main/java/com/zl/d26/document/exception/DocumentValidationException.java | 校验异常（IllegalArgumentException 子类） |
| src/main/java/com/zl/d26/document/exception/DocumentParseException.java | 基础解析异常 |
| src/main/java/com/zl/d26/document/model/DocumentStatus.java | 状态枚举 PENDING/PROCESSING/COMPLETED/FAILED |
| src/main/java/com/zl/d26/document/model/KnowledgeDocument.java | 领域模型 |
| src/main/java/com/zl/d26/document/po/KnowledgeDocumentPO.java | 持久化对象 |
| src/main/java/com/zl/d26/document/mapper/KnowledgeDocumentMapper.java | BaseMapper 接口 |
| src/main/java/com/zl/d26/document/dto/DocumentRegisterRequest.java | 登记请求 DTO |
| src/main/java/com/zl/d26/document/dto/DocumentView.java | 文档视图 |
| src/main/java/com/zl/d26/document/dto/DocumentImportView.java | 导入结果视图（含 duplicate） |
| src/main/java/com/zl/d26/document/support/Sha256ContentHasher.java | SHA-256 哈希 |
| src/main/java/com/zl/d26/document/support/FileUploadValidator.java | 服务端文件校验 |
| src/main/java/com/zl/d26/document/support/BasicDocumentParser.java | 最小基础解析 |
| src/main/java/com/zl/d26/document/support/DocumentConverter.java | 领域/PO/视图转换 |
| src/main/java/com/zl/d26/document/service/DocumentRegistrationService.java | 登记服务 |
| src/main/java/com/zl/d26/document/service/DocumentImportService.java | 导入服务（状态机/去重/解析） |
| src/main/java/com/zl/d26/document/controller/DocumentController.java | 登记与导入入口 |

> 注：主源码实际为 17 个新增文件（含 DDL），上表逐行列出。

## 2. 新增测试源码（6 个，30 个测试方法）

| 路径 | 测试方法数 |
|---|---|
| src/test/java/com/zl/d26/document/FileUploadValidatorTest.java | 8 |
| src/test/java/com/zl/d26/document/BasicDocumentParserTest.java | 3 |
| src/test/java/com/zl/d26/document/Sha256ContentHasherTest.java | 4 |
| src/test/java/com/zl/d26/document/DocumentRegistrationServiceTest.java | 5 |
| src/test/java/com/zl/d26/document/DocumentImportServiceTest.java | 6 |
| src/test/java/com/zl/d26/document/DocumentControllerTest.java | 4 |

## 3. 既有文件最小增量（2 个）

| 路径 | 改动 |
|---|---|
| src/main/resources/application.yml | 新增 spring.servlet.multipart 与 app.ai.document 配置块 |
| src/main/java/com/zl/d26/common/GlobalExceptionHandler.java | 新增 MaxUploadSizeExceededException 处理 |

## 4. 未改动项
- 现有三表（chat_session/chat_message/prompt_template）与对应代码零改动。
- 未新增 POM 依赖（文档解析器在 D26 模块 POM 中已存在，本轮未强制接线）。
