# D41-R03 整改测试执行摘要（真实执行记录）

| 步骤 | 命令 | 结果 | 输出文件 |
|---|---|---|---|
| 0 基线锚定（改码前） | mvn -B -f D41\pom.xml -pl D41_TASIIEM test | Tests run: 78, F:0, E:0, BUILD SUCCESS | test-output-baseline.txt |
| 1 先红（仅新增用例，实现未改） | mvn test -Dtest=PdfTableR03MechTest | Tests run: 8, **Failures: 5**（T1/T2/T3/T4/T5 复现成立） | test-output-red.txt |
| 2 转绿（实现完成后） | mvn test -Dtest=PdfTableR03MechTest | Tests run: 8, F:0 全 PASS | test-output-green.txt |
| 3 全量回归 | mvn -B -f D41\pom.xml -pl D41_TASIIEM test | **Tests run: 86, Failures: 0, Errors: 0**（78 既有零退化 + 8 新增），BUILD SUCCESS，EXIT=0 | test-output-full.txt |

## 225 格复算（同工具同口径，工具校准 diff=0）

- 整改前：value_hit 27 / unaligned 67 / missing 131 / **wrong_value 0**（calib-prerun-cell-diff.json）
- 整改后：value_hit **195** / unaligned **30** / missing **0** / **wrong_value 0**（post-cell-diff.json）
- 网格还原率 41.8% → 86.7%；剩余 30 格=R01 真值列名转写差异（p397 C1110ES↔C11010ES、p398 C111020□S↔C11020□S），值全部在场。

## 在线 mock 闭环（r5 实例）

11/11 ALL-PASS（online-verify-r03rerun.json）；真实手册在线导入 NOT RUN——2MiB 上限 4001 显式拒绝（online-real-manual-r03.json 记录实况）。
