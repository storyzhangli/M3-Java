# D28 迭代状态记录

## 真实状态（截至最终验证）

| 阶段 | 状态 | 证据 |
|---|---|---|
| 骨架扫描 | 已完成 | `D28-design-conclusion.md` |
| 方案设计 | 已完成 | `D28-design-conclusion.md` |
| 编码实现 | 已完成 | 23 主源 + 6 测试，`D28-01-file-inventory.md` |
| 编译 | 已通过 | `logs\D28\01-compile.log` BUILD SUCCESS |
| 全量单测 | 已通过 | 219/0/0/0，`D28-08-test-summary.md` |
| 真实切分对比 | 已通过 | 23 vs 13 块，`D28-05-comparison.md` |
| 真实 PDF 验证 | 已通过 | `D28-07-real-pdf-verification.md` |
| 正式文档 | 已完成 | `docs\proj_docs\D28-*.md` 两份 |
| 端到端真实数据库 | 未执行 | 需先在 ai_db 执行 DDL，与本任务主线无关 |

## 关键结论

- 固定长度策略产出 23 块，长度 352–495，存在 1376 字符重叠（Overlap 生效），未覆盖 0。
- 结构策略产出 13 块，长度 200–984，无重叠，未覆盖 0，更贴合段落语义。
- 两策略均无空块；句末标点清洗前后守恒（167=167）；来源页码/段落定位完整。
- 所有长度口径为 UTF-16 code unit，**不等同于 Token 数**。

## 诚实缺口

- 未实现 Embedding/VectorStore/向量检索/RAG 问答/Agent/Tool/MCP（任务硬边界）。
- 真实 MySQL 端到端 HTTP 上传未做；当前无鉴权；真实模型未配置 API Key 未验证。
- 扫描件仅识别并提示需 OCR，不实现 OCR。
- 未执行任何 git commit/push；temp_docs 与 logs 未自动删除，交由用户处理。
