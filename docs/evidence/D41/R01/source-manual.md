# D41-R01 真实设备手册来源记录（source-manual.md）

## 1. 样本元数据

| 字段 | 值 |
|---|---|
| 标题 | 台达 ASDA-A2 系列高机能通讯型伺服驱动器 应用技术手册（中文版） |
| 制造商 | 台达电子工业股份有限公司（Delta Electronics, Inc.） |
| 版本标识 | Revision November, 2008；Doc. Name: **2008PDD23000005**（页脚印刷，见 p71/p339 等页） |
| 文件 | `docs/temp_docs/D41/r01/ASDA-A2-gongkong-mirror.pdf`（**不提交进 Git**，见 §4 许可说明） |
| 下载 URL | `http://fs.gongkong.com/files/technicalData/201002/2010022010583200003.pdf` |
| 下载通道性质 | 中国工控网（gongkong.com）公开镜像托管；原始官方来源为台达官网下载中心（delta.com.tw / deltaacdrives.com） |
| 官方域直连情况 | `https://deltaacdrives.com/Delta-ASDA-A2-User-Manual.pdf` 实测 HTTP 403（反爬拦截，2026-11-24 curl 记录），故改用公开镜像并在本文件如实记录通道性质 |
| 访问日期 | 2026-11-24（Asia/Shanghai） |
| HTTP 状态 | 200，`SIZE=8507706` bytes |
| SHA-256 | `6050f8f300db6ad08eb166bb00238cf1b8d5e58efc2294e54e60f919d8c2a60a`（与 `cell-diff.json → meta.pdfSha256` 同源一致） |
| 页数 | 410 页（主路径 pageLines 最大页键，p1 封面 0 字符） |
| 文本层 | **真文本层 PDF**（非扫描件）：p2=505ch、p3=852ch、p4=875ch、p5=153ch（逐页字符数样本坐实）；含内嵌子集字体（JOMKAB+Wingdings-Regular 等图标字体无 Unicode 映射，见 error-list E6） |

## 2. 主路径全文件解析结果（真实执行，非抽样）

- 命令与探针：`docs/temp_docs/D41/r01/tools/D41RealManualProbe.java`（classpath=模块 target/classes + mvn 依赖树，PDFBox 3.x 由模块依赖提供）
- 结果：**368 张表 / 4.49 s / 退出码 0**；完整原始输出 `docs/temp_docs/D41/r01/tools/probe-run.log`
- 逐格对账范围：第十二章 12.6「伺服电机外型尺寸」规则有线表 **p396/p397/p398**（印刷页码 12-19/12-20/12-21，5/6/7 列 × 15 行，含型号列=备件选型维度、数值列=机构尺寸规格）——对应整改要求的「故障码/备件/规格参数」中**备件型号+规格参数**类样本。
- 异警/故障码域（第十一章 p354-368）为合并单元格长文排版，主路径碎片化检出（见 error-list E5），纳入错误清单而不选作逐格对账样本（其 truth 无法以等宽网格定义，强行对账将失真）。

## 3. 人工核定方法（区分证据类别声明）

- 真值来源：对 p396-398 三页 **原始 PDF 页面整页图像**逐格目视转录（截图为 PDF 渲染器直出，非 OCR），得 `source-cells.json`（225 数据格，unit=mm 以页脚 NOTE 1 为依据）。
- 抽取值来源：D41 实际解析主路径单次全文件解析结果按页过滤，**未做任何后处理/人工修正**，得 `extracted-cells.json`。
- 对账：程序化行标签对齐 + 值字符串等值比对（方法声明见 `cell-diff.json → meta.diffMethod`），逐格结果 225 条全量落盘 `cell-diff.json`。
- 证据类别：本节属「真实数据 + 人工核定 + 自动化对账」三类；未声称在线入库核验（DB 终态归 r4 在线核验任务）。

## 4. 许可与合规说明

- 手册内容版权归台达电子；镜像页与文档内未附带明确开放再分发许可声明。
- 遵循整改书口径「许可不允许时，仅提交来源 + 哈希 + 最小核验数据」：**原始 PDF 不入库**（`docs/temp_docs/` 不纳入提交，§5 有 git ignore 核验记录）；入库证据仅为元数据、逐格对账 JSON（机构尺寸数值属事实数据，非手册文本再分发）与错误分析。
- 真值不因抽取偏差调整；抽取不含错值（wrong_value=0），缺失与列错位如实记录于 `error-list.md`。

## 5. 卫生核验（执行记录）

- `git check-ignore -v docs/temp_docs/D41/r01/ASDA-A2-gongkong-mirror.pdf` 与 `git status --porcelain docs/temp_docs` 实测结果见整改提交前审查记录（implementation-review.md §卫生）。若 ignore 规则不覆盖，则提交时仅 add 证据目录（`docs/evidence/D41/R01/`），不 add temp_docs。
