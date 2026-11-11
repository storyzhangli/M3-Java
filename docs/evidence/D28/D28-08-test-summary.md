# D28 测试摘要

## 全量结果（真实执行）

- 命令：`mvn -f D28\pom.xml clean test`
- 结果：**Tests run: 219, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**
- 日志：`logs\D28\02-test.log`
- 原始报告：`D28\D28_TCASS\target\surefire-reports`
- 归档报告：`docs\evidence\D28\surefire-reports`（69 个文件）

## 本迭代新增 Chunk 测试（50 个，全部通过）

| 测试类 | 数量 | 结果 |
|---|---|---|
| `ChunkPropertiesTest` | 10 | 通过 |
| `clean\TextCleanerTest` | 16 | 通过 |
| `split\FixedSizeSplitterTest` | 11 | 通过 |
| `split\StructureSplitterTest` | 8 | 通过 |
| `service\DocumentChunkServiceTest` | 5 | 通过 |
| 合计 | 50 | 全部通过 |

## 关键能力与对应测试

| 能力 | 关键测试 |
|---|---|
| 换行统一、空白归一、空行折叠 | `normalizesLineEndings`、`collapsesHorizontalWhitespace`、`collapsesRepeatedBlankLines` |
| 异常空白字符（NBSP/全角） | `normalizesAbnormalWhitespaceChars` |
| 结构识别（标题/列表） | `preservesHeadingAndListStructure` |
| 中文/中英混合/特殊字符 | `chineseLengthAndContentCorrect`、`mixedChineseEnglishStaysIntact`、`specialCharactersAreNotLost` |
| 长度边界与空块 | `chunksRespectMaxSizeAndSequentialIndex`、`noEmptyChunks` |
| Overlap=0 / Overlap>0 | `overlapZeroProducesAdjacentNonOverlappingChunks`、`overlapPositiveStaysWithinConfiguredBound` |
| 超长硬切与尾块合并 | `longUnbrokenStringIsHardCut`、`noChunkShorterThanMinAfterTailMerge` |
| 代理对保护 | `neverSplitsSurrogatePair` |
| 跨页/跨段来源 | `crossPageSourcesCoverBothPages`、`allParagraphIndicesArePreserved` |
| 无文本丢失 | `bothStrategiesRunWithoutTextLossOrEmptyChunks` |
| 多版本区分 | `chunksOfDifferentVersionsAreDistinguished` |

## 边界场景覆盖

任务书列出的 16 个边界场景（空文本、仅空白、极短/连续短段落、超长段落/单句、
中文、中英混合、特殊字符、Overlap=0/>0/越界、多页、跨页、多版本、极短尾段）
均有对应测试；Overlap≥Size 的拒绝由 `ChunkPropertiesTest` 覆盖。
