# KP3 冻结件 —— 历代资产在位与等价核验

时刻：2026-11-26 P0。三项全过，KP3=PASS。

## 3-1 评测器零漂移复建

- 9 源（docs\temp_docs\D37\evaluator\src\d37eval\）javac 重编 **EXIT=0**（零源码改动）。
- 通道复原：classpath = D37\D37_RAG_ESAB\target\classes（UrlSafetyPolicy）+ jackson-databind/core/annotations **2.19.0**（D42 同口径，d42_rerank-experiment.ps1 L83-88 实证）。首次裸 javac 失败=漏第三方依赖，非资产问题。
- SelfTest：**PASS=75 FAIL=0 ALL SELFTESTS PASSED**（kp3-evaluator-selftest.txt，EXIT=0）=D42 口径 75/75 复现。
- 产物目录：docs\temp_docs\D43\tools\d37eval_out（P3 执行复用）。

## 3-2 D41→D43 表格夹具真值对账

- FIXTURE-SHA256.txt 10 行（fixture-truth.json + 9 夹具）**MATCH=10 MISMATCH=0**（Get-FileHash 逐行实算）。
- 前置：FIX-D43-001（manifest d42→d43 名迁移重生成）后状态；99/100→100/100 链条闭环（fix-001.md）。

## 3-3 决策文件与协议在位

- docs\proj_docs\D42-01/D42-02/知识笔记 三件在位；docs\evidence\D42\probe\rerank-protocol.json 在位（内容面锚 c377bb2f 口径出处，KP2 已复算实证）。
- D37 冻结基线：datasetHash=75CC8D83…17CFE（C01 段实测复验）+ baseline 20×17 双后端目录在位。
- D41 审计口径锚继承：195/30/0/0、wrong_value=0（引用不重测，D43 表格域以 C16 225 格对账承接）。

## 冻结声明

P3 评估必须复用 3-1 编译产物与 2.19.0 classpath；任何"评测器跑不起来"先对照本件通道，禁止另起炉灶改源码。
