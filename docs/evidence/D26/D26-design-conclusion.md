# D26 方案设计结论（Design）

> 基于真实代码与 D25 KnowledgeDocument 字典，不假设未扫描组件。

## 1. 现有 Schema 是否满足
- 满足字段语义来源：documentId、title、version、status、updatedAt、contentHash 均可在 D25 字典找到对应。
- 物理表不存在：需新增一张 `knowledge_document` 表（最小字段集），不改动现有三表。
- 名称映射：任务书最小字段 `source` 对应 D25 的 `sourceSystem`；以 `source` 列承载并注明，不另起体系。

## 2. 新增文件（均在 com.zl.d26，主源码零删除）
- DDL：resources/db/schema-knowledge-document.sql
- config：document/config/DocumentImportProperties（app.ai.document.*）
- model：document/model/KnowledgeDocument、DocumentStatus（枚举）
- po + mapper：document/po/KnowledgeDocumentPO、document/mapper/KnowledgeDocumentMapper
- dto：document/dto/DocumentRegisterRequest、DocumentView、DocumentImportView
- 支持类：document/support/FileUploadValidator、BasicDocumentParser、Sha256ContentHasher
- service：document/service/DocumentRegistrationService、DocumentImportService
- controller：document/controller/DocumentController
- 异常：document/exception/DocumentValidationException、DocumentParseException

## 3. 登记与导入职责分离
- 登记（Registration）：只写元数据，生成 documentId，落 PENDING，不接触文件字节。
- 导入（Import）：接收 MultipartFile，先做服务端校验，再 contentHash 去重，驱动状态机并做基础解析；
  登记不读文件、导入不决定元数据命名规则，两者通过 documentId 关联同一文档行。

## 4. 状态机
- PENDING（登记即此态）→ PROCESSING（开始读取/解析）→ COMPLETED（解析成功）/ FAILED（异常，含错误码与摘要）。
- 任何异常路径都保证从 PROCESSING 收敛到 FAILED，不允许长期停留；不吞异常伪造成功。

## 5. 文件校验与重复策略
- 服务端校验（不依赖前端）：零字节、业务可配大小上限、类型白名单（结合扩展名与内容类型，不仅看扩展名）、
  UTF-8 解码失败、读取异常。容器 multipart 限制与业务限制分层，业务限制在服务内独立可配。
- 重复：对原始字节算 SHA-256（contentHash），表上 content_hash 唯一约束兜底；
  重复导入不产生第二行、不进入第二次处理流程，返回既有记录。

## 6. 异常与一致性
- 校验失败抛 DocumentValidationException（IllegalArgumentException 子类），经现有全局处理器归 PARAM_INVALID。
- 解析失败落 FAILED 并记录错误信息；并发重复由 DB 唯一约束 + 捕获完整性冲突保证。
- 不入库密钥、不写本机绝对路径；文件内容不整段持久化（仅记 content_length 与哈希）。

## 7. 测试覆盖方案
- 真实字节/真实 MockMultipartFile 的校验测试：空、超大、非法编码、非法类型、重复。
- 状态机单测：成功 COMPLETED、解析失败 FAILED、updatedAt 随状态变更。
- 并发重复：CountDownLatch + 模拟唯一约束，断言无第二行。
- 控制器 MockMvc 切片：入口协议与统一 Result；不把 Mock 当作真实文件校验。

## 8. 边界
- 不实现 Embedding、VectorStore、Chunk 切分、RAG 检索/问答、Agent/Tool/MCP；
- 文档解析器依赖虽在 POM，本轮仅做最小 UTF-8 解码与可用性判断，不做全量 Tika 接线。
