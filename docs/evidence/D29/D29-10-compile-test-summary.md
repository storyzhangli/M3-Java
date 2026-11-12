# D29 编译与测试摘要

## 1. 环境

- JDK 21、Maven 3.9.16、Spring Boot 3.5.16、Spring AI 1.1.8。

## 2. 编译

- 命令：`mvn -f D29/pom.xml clean compile`
- 结果：BUILD SUCCESS（D29 与 D29_EBD 均成功）。

## 3. 测试

- 命令：`mvn -f D29/pom.xml clean test`
- 结果：BUILD SUCCESS。
- 汇总：**Tests run: 266, Failures: 0, Errors: 0, Skipped: 0**。
- 测试类：41 个（基线 34 + 本轮新增 7）；Surefire 报告 41 份（txt/xml 各 41）。

## 4. 真实调用与受控模拟的区分

- 真实调用：本地哈希词面模型真实嵌入中文/英文/中英混合/长短文本，验证维度、L2 归一化、
  无 NaN/Infinity、余弦相似度与确定性（见 D29-04）。
- 受控模拟：重试的故障注入、兼容性的异模型元数据、模型切换均为测试桩/元数据驱动，
  明确标注，不声称真实接入第二个模型或云端服务。

## 5. 日志

- 编译日志：logs/D29/01-compile.log
- 测试日志：logs/D29/02-test.log
