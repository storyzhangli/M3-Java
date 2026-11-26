# D43 R01 端到端测试报告（RAG 全主链 × 六域 × 双态）

- 迭代：D43/R01（集成回归与阻断修复，禁新功能）｜基线 commit `c47be85`
- 生成时间：2026-11-26（终态复核后）｜实例终态：WRAPPER_PID=31420 干净 disabled（AI_MODE=mock）
- 口径：阻断=链路断裂/契约破坏/安全漏洞/数据不一致（必修）；质量=冻结基线已知 FAIL 族（只分类）
- 外部调用实耗：DeepSeek 0 / 百炼 0 / 远程 rerank 0（熔断#7 红线全程保持）

## 1. 三维矩阵汇总（链节 × 六域 × 双态）

| 链节 | 主域 | disabled 态 | enabled 态 | 证据 |
|---|---|---|---|---|
| 知识库管理（register/import/index/delete/publish） | 解析/切分 | 全链 200 语义正确（publish 端点参数形态未定=观测缺口） | PT 4 写面+one 3 写面 ADMIN-only；USER=4030；审计全落 | lifecycle run5 49/49、matrix-post 285 格、matrix-post-armo 50 格 |
| 表格抽取（PDF/Word） | 解析 | FIX-001 manifest 修复后 C01 继承 100/100；SICK 样本 21 格全对（前代） | 同（无权限面差异） | c01-mvn-inherited/post-fix-001、D41 资产复验 |
| 清洗切分 | 切分 | A2 短/空白/34KB 长文三夹具切分符合预期（long 需含换行，单行不切分=设计内） | 同 | exceptions-run4 |
| Embedding（本地哈希 384） | Embedding | 检索 embedding 正常；A5 one_ 黑洞只打检索面（ask=200 REFUSAL=生成 mock 正常） | 同 | exceptions-run4、BC-D43-009 |
| 检索（Top-K/threshold/过滤） | 检索 | 20 题 hitRate@5=1.0；PG 双锚一致；权限过滤 D39 面不回退 | D39 T1–T10 语义不回退（矩阵观测行） | run-1810 metrics、c18-fp-final.txt |
| 生成与回答（mock） | 生成 | REFUSE/ANSWER 决策面与 D37 冻结逐轮等分布 | 同 | run-1810 vs D37 基线 |
| 引用（citation） | 生成/权限 | C12 边界声明：mock 无真实生成→sources 恒空、citationLegality=null=**可测性边界非缺陷**；无证据拒答 citations=0 不误放行已证（A5 权威口径） | 同 | BC-D43-009、run-1810 metrics denominators |
| 权限（guard/审计/越权） | 权限 | 直通零差异（guard disabled 语义=H 设计） | G2/G4 收口：DENIED 12+18、ALLOWED 6、LEAK=0 | C15 dump T0=347、guard 单测 10/10 |
| 更新删除（版本指针/tombstone） | 同步元数据 | 删除闭环 SYNCED；同版本重放=FIX-004 通道恢复；tombstone→恢复瞬态窗口=BC-D43-007 | M-D13 族幂等差异已裁决（资产态） | lifecycle run5、final-clean.txt |

## 2. 关键结果数字（全部实跑）

- **C05 评测回归**：run-1757 diff=39（资产污染，熔断#2）→ 资产修复 → run-1801 diff=20（seeding 瞬态窗口，BC-D43-007）→ **run-1810 质量列 diff=0**；labels 逐轮等分布（51/3=17、30/3=10、6/3=2 与冻结 17/10/2 一致）；hitRate=1.0、refusalAccuracy=1.0、falseAnswerRate=0.0
- **越权矩阵**：pre 30 缺陷格（PT 12 + one 面用户直达）→ post 主矩阵 285 格 + armo 50 格 **MISMATCH=0 / PRE-DEFECT=0 / LEAK=0**；AUDIT 6→79→91
- **C15 审计差值**：`prompts.*` DENIED=12 / ALLOWED=6；`one.vector-index` DENIED=18 / ALLOWED=6（与预期精确一致，T0=347 dump）
- **A1–A6 异常注入**：**7/7 PASS**（A4 双态含 FAULT 臂：firstIndex=5000+SIMULATED 锚 4 次+retry COMPLETED=FAILED≠EMPTY+恢复收敛）
- **C16**：225 格 **195/30/0/0** 不回退，wrong_value=0 保真红线守住
- **C17**：证据区 sk-/Bearer/完整 token 零命中；principals.local.json 被 .gitignore L40 覆盖；源码/yml 无硬编码密钥
- **C18 清理**：本迭代资产清零（KD D43-A* 14→0、chunk 1→0、vi 1→0、PG e2e 6+1→0，恢复纯冻结 8 行）；**双锚恒等**：ID 面 `50c457b6…`、内容面 `c377bb2f…`（V2 口径）；legacy d32eval 24 孤 chunk 与 sync 3 行 DELETE SYNCED = 前代正当资产，不触碰
- **测试基线演进**：继承 99/100（红=FIX-001 复现器）→ 100/100 → guard 定向 10/10 → **终态全量 110/110 FAIL=0**（mvn-test-final，surefire 21 文件复核）

## 3. 阻断修复（4 项，全部 FIXED-VERIFIED）

| id | 域 | 一句话 | 验证锚 |
|---|---|---|---|
| FIX-D43-001 | 解析/测试资产 | 骨架迁移 FIXTURE-SHA256 manifest 未重生成 | 100/100 |
| FIX-D43-002 | 权限 | PromptTemplateController 4 写面无守卫 | post+C15+单测 |
| FIX-D43-003 | 权限 | OneVectorIndexController 3 写面无守卫（G4 不对称） | armo 50 格+单测 |
| FIX-D43-004 | 数据一致性 | indexChunks 同版本重建 PG 残留恒 COUNT_MISMATCH | run5 49/49 |

质量型 bad-case 全部只分类（见 `badcase-classification.csv`，12 条，六域+流程/资产/环境类）；**UNATTRIBUTED=0**。

## 4. 事故与教训（如实）

1. **mtime 陷阱**：Move-Item 备份恢复保留旧时间戳 → Maven 增量静默跳过 → "修复态全量 100/100"曾实际编译旧类；touch 强制重编后 javap 实证。副作用：反向证实 pre 缺陷轮活体证据有效。
2. **管道截断事故**：长评测脚本外层 `Select -First 20` 提前终止整链且 throw 无痕 → 教训=长脚本全量重定向再读尾。
3. **评测与 seeding 并发**：DELETE tombstone 瞬态窗口被 run-1801 撞上（BC-D43-007）→ 规程=seeding 后先 ask 快检再起跑评测。
4. **熔断#2 正确处置**：diff≠0 先定资产后定代码，两疑点均闭环（RE-08/09=CONCAT 补零误报维持；totalAfter=9=A4 残留 1 行，删除后=8）。

## 5. 风险与环境干扰

- **OBS-D43-X2**：外部 18080 IDEA 实例（PID 30956 系）全程存活，两度清共享表（knowledge_document/audit 221→0）干扰矩阵与评测资产。**建议迭代窗口停机该实例**（用户未决，未代杀进程）。
- 重启预算 8/8 用尽=本迭代后段一切验证均在 #8 实例（干净 disabled）上完成，无重启余量属既定纪律边界。

## 6. 缺口清单（诚实声明口径）

- A6 过期 token（CREDENTIAL_EXPIRED）案例未注入（机制在位，未播夹具）——D44 候选。
- publish 端点请求体形态未定（5000 参数错误，不阻塞主链）。
- C12 引用链在 mock 态不可闭环（生成 sources 恒空）=可测性边界，已在 A5 口径下用 citations=0 不误放行兜底。
- L5 跨机 = NOT RUN（单机 N/A）。
- 词面弱相关新数据集、hybrid+rerank 组合臂、sync 终态行保留策略 = D44 议题。
