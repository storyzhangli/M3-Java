# KP1 能力边界评估记录（D41，2026-11-24）

## PDFBox 3.0.7（唯一表格能力来源，H3 冻结）

- `dependency:tree` 实证：`org.apache.pdfbox:pdfbox:3.0.7:compile`（+pdfbox-io/fontbox 3.0.7），
  经 `spring-ai-pdf-document-reader:1.1.8` 传递；父 POM 无 pin，运行版本以依赖树为准。
- 接线侧 grep：`org.apache.pdfbox` 仅 1 文件（PdfDocumentParser）——主路径既有能力=PDFTextStripper。
- **坐标探针**（`kp1-coordinate-probe.json`）：自定义 Stripper 子类 `writeString(String, List<TextPosition>)`
  回调收集 xDirAdj/yDirAdj/width/fontSize，7 PDF/9 页、**97 个真值格 100% 命中，最大偏差 0.00pt**
  → "行/列聚类识别表格"在既有 API 上完全可行，无需 tabula/camelot/deepdoctection（H3 红线合规）。
- 边界：PDFBox 文本 API **不暴露划线（ruling line）坐标** → 有线/无线表同样依赖 x 聚类，
  列结构不稳时标 LOW_CONFIDENCE（KP4 口径）。

## Tika / POI（在依赖树但零接线，D3 裁决保持）

- `spring-ai-tika-document-reader:1.1.8` → `tika-core:3.3.1` + `tika-parsers-standard-package:3.3.1`
  （含 microsoft 模块 = POI 传递、jbig2-imageio）。
- 接线侧 grep：`org.apache.tika` / `org.apache.poi` 业务代码 **0 命中**；上传白名单
  txt/md/pdf（DocumentImportProperties），docx 由 FileUploadValidator 既有语义拒绝。
- R01 决策：不接 docx/Tika（避免动核心 import 路径），Word 表格能力诚实退化
  `WORD_NOT_SUPPORTED`；Tika/POI 接线评估列 D42 候选。

## OCR（absence 实证，D4 裁决）

- `dependency:tree -Dincludes=net.sourceforge.tess4j` → **TESS4J_HITS=0**（无引擎）。
- 主路径：图片表/扫描件不崩溃；`table.ocr.enabled` 默认 false 仅接口占位；
  扫描件既有语义 `NO_EXTRACTABLE_TEXT` FAILED 不变；图片表标 `IMAGE_TABLE_NOT_PARSED`、
  置信度 N/A；真实 OCR 识别 NOT RUN（无引擎，不伪造）。

## 新表格端点分级（`kp1-table-endpoint-inventory.csv`）

3 读面（文档级 checkReadable）+ 1 写面（ADMIN+审计）+ 既有端点零改动/追加语义标注。
