# D26 测试结果摘要（Test Summary）

> 数据来自真实执行 `mvn clean test` 与 target/surefire-reports，非估算。

## 1. 总体结果

| 指标 | 真实值 |
|---|---|
| 构建结果 | BUILD SUCCESS |
| 总测试数 | 153 |
| 失败 Failures | 0 |
| 错误 Errors | 0 |
| 跳过 Skipped | 0 |
| 本轮新增测试方法 | 30（6 个测试类） |
| 测试 profile | test（Surefire 激活，挂 mockito-core Agent） |

## 2. 任务书 10 类场景覆盖映射

| 场景 | 覆盖测试（真实字节/真实状态） | 结果 |
|---|---|---|
| 正常导入 | DocumentImportServiceTest#newImportCompletes；DocumentControllerTest#importSuccess | 通过 |
| 空文档 | FileUploadValidatorTest#emptyFileRejected | 通过 |
| 超大文件 | FileUploadValidatorTest#oversizedFileRejected | 通过 |
| 非法编码 | FileUploadValidatorTest#illegalEncodingRejected | 通过 |
| 不支持类型 | FileUploadValidatorTest#unsupportedExtensionRejected / unsupportedContentTypeRejected | 通过 |
| 重复导入 | DocumentImportServiceTest#duplicateHashReturnsExistingWithoutSecondFlow | 通过 |
| 导入失败 | DocumentImportServiceTest#unexpectedRuntimeMarksFailed | 通过 |
| 解析失败 | DocumentImportServiceTest#parseFailureMarksFailed；BasicDocumentParserTest | 通过 |
| 状态更新时间 | DocumentImportServiceTest#statusUpdateTimeAdvances | 通过 |
| 并发重复导入 | DocumentImportServiceTest#concurrentDuplicateHandledByUniqueConstraint | 通过 |

## 3. 校验真实性说明

- 文件校验类用例使用真实字节数组与真实 MockMultipartFile，未用 Mock 成功代替真实校验。
- 读取失败路径用 Mockito 模拟 MultipartFile#getBytes 抛 IOException，验证服务端拒绝逻辑。
- 并发重复通过模拟 DB 唯一约束（DuplicateKeyException）验证；真实唯一约束在 DDL 中以 uk_content_hash 落地。

## 4. 证据指针

- 原始 Maven 日志：logs/D26/01-compile.log、logs/D26/02-test.log
- Surefire 报告副本：docs/evidence/D26/surefire-reports（27 个测试类的 txt + xml）

## 5. 诚实标注

- 本轮为单元/切片测试，未启动真实 MySQL 执行端到端 HTTP 上传；DDL 已提供，需在真实库执行后做端到端验证。
- 未验证真实模型 remote（无 AI_API_KEY），与本任务无直接关系。
