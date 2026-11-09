# D27 迭代状态记录（Day 27：PDF Word 解析与来源元数据）

## 1. 状态总览（截至 2026-11-09）

| 维度 | 状态 | 证据 |
| --- | --- | --- |
| 需求扫描与基线 | 已完成 | docs/temp_docs/D27/D27-baseline.md、logs/D27/dir-tree.log |
| 技术选型与方案设计 | 已完成 | docs/evidence/D27/D27-design-conclusion.md |
| 主源码编码 | 已完成 | 见 D27-file-inventory.md |
| 编译 | 已通过（131 主源文件） | logs/D27/01-compile.log（BUILD SUCCESS） |
| 单元测试 | 已通过（168 测试，0 失败） | logs/D27/02-test.log、surefire-reports |
| 真实 PDF 解析 | 已通过（佳能手册，11 页） | docs/evidence/D27/D27-real-pdf-verify.md |
| 真实 MySQL 端到端 | 未执行 | 需先执行两份 DDL |
| Git commit/push | 未执行（按约束不提交） | git status/diff 复核 |

## 2. 本轮交付物

- 两份正式文档（docs/proj_docs）：
  - D27-PDF解析与来源定位功能实现全量说明.md
  - D27-PDF解析与来源元数据技术原理及操作实践指南.md
- 证据（docs/evidence/D27）：设计结论、文件清单、测试摘要、迭代状态、真实 PDF 验证记录、surefire-reports。
- 日志（logs/D27）：dir-tree.log、01-compile.log、02-test.log。
- 临时（docs/temp_docs/D27）：基线、cp.txt、RealPdfVerify.java/.class（保留，不自动删除）。

## 3. 关键技术决策（已落地）

- 直接使用 PDFBox 3.0.7（已在 classpath，零新增依赖），逐物理页提取，保留 1-based 页码与页面边界。
- 原始 PDF 与提取文本分离：knowledge_document 存元数据，knowledge_document_page 存页面文本（含 blank 标记）。
- 校验器白名单加 pdf/application/pdf；PDF 跳过仅用于文本的 UTF-8 解码。
- 导入按扩展名路由；FAILED 文档允许同内容重试；重新解析先删后插（同事务）。
- 新增来源定位端点：GET /{documentId}/pages、GET /{documentId}/pages/{pageNumber}。

## 4. 诚实缺口

- 真实 MySQL 端到端 HTTP 上传未做：需在 ai_db 执行 schema-knowledge-document.sql 与 schema-document-page.sql 后再验证。
- 当前无鉴权，不假设权限已强制；X-Dev-User 仅归属标识。
- 真实佳能素材为纯英文，中文/混合由真实字体单测验证。
- 真实模型 remote 无 AI_API_KEY 未验证（与本任务无关）。

## 5. 边界（明确未实现）

未实现 OCR 体系、Embedding、VectorStore、Chunk 智能切分、向量检索、RAG 问答、Agent/Tool/MCP；页面级文本不等于 RAG Chunk；未做无关重构。
