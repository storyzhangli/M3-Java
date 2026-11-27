# 失败案例文档（D44 汇总 | RAG 阶段全量失败知识资产）

> 六域分类法：解析域/检索域/生成域/权限域/同步元数据/资产·环境（+工具误报）。每条含现象、根因、处置、证据。
> 素材：D43 badcase-classification.csv 12 条 + FIX-D43-001..004 根因卡 + D42 D-EX 偏差 + D41 R03 机理修正 + D44 registry 5 行。

## A. 阻断型（已修复并回归 VERIFIED）

### FC-01 = FIX-D43-001｜测试夹具清单名漂移（D44 复发为 FIX-D44-001）
- **现象**：`PdfTableFixtureTruthTest` 报"夹具缺失 d43-fault-code.pdf"，首跑 109/110。
- **根因**：物理夹具目录已 d44- 更名而 `FIXTURE-SHA256.txt` 清单仍残留 9 行 d43- 前缀（清单与物理文件双维护无同步机制）。
- **处置**：用户授权机械修正；重生成清单（9 夹具 SHA-EQUAL 实证冻结面完好）+ 原文件 .bak + adjudicatedChanges 登记；复跑 110/110。
- **教训**：同根因两代复发——清单应由脚本从物理目录生成，非手工维护。
- **证据**：fix-registry.jsonl / FIXTURE-SHA256.txt.anchor-d43names.bak / compile-test2-agg.txt

### FC-02 = FIX-D43-002/003（D43）｜管理面写端点无守卫
- **现象**：矩阵 pre 轮 prompts 4 写面+one 3 写面 DENIED 缺失=越权写通道。
- **根因**：D39 权限代数落地时 PromptTemplate/OneVectorIndex 控制器遗漏 guardAdmin 接线。
- **处置**：guard 注入+审计双分支；post 轮 285+50 格全绿、DENIED 取证入 d44_admin_audit。
- **证据**：D43 fix-registry + 本轮 c08-summary（矩阵工具迁移复跑等价）。

### FC-03 = FIX-D43-004｜import PARSE_FAILED 审计留痕缺失
- **现象**：同文档重放误报幂等，PARSE_FAILED 无审计行。
- **根因**：异常分支未走审计写入；第二样本 8028100（21 格）复现确认。
- **处置**：审计补写+4 单测；D41-R03RE-08/09 夹具固化。
- **证据**：D43 fixes\fix-004.md + 本轮 C17 恒等复验。

### FC-04 = OBS-D44-X1｜"lifecycle 自愈"叙事不实（本轮新证）
- **现象**：seeding DELETE 后文档卡 DEPRECATED，activate 轮询 180s 两连败（D43 曾记"自愈"）。
- **根因**：KnowledgeDocumentMapper src/main **零** DEPRECATED→ACTIVE 回写路径；D43 观察到的恢复=其他通道假象。
- **处置**：D44ActivateFix.java 数据面显式回写（修复前后 18 行快照证明仅动 3 语料行）；双锚恒等恢复。
- **教训**：**框架叙事必须以代码路径为证**；轮询超时≠暂时故障，须查回写链存在性。
- **证据**：kp2-activate-fix.txt / kp2-seed-prestate/poststate

## B. 质量型（冻结基线已知 FAIL 族，只分类不修）

### FC-05 = BC-D43-001｜REFUSAL_FALSE_NEGATIVE 30 格（10 题×3 轮）
- **现象**：有证据题被 mock 评估决策拒答。四态 0/20/0/0 同构复现于本轮。
- **根因**：OFFLINE 无 ChatModel 通道固定形态（reason="AI_MODE 非 deepseek;无 ChatModel"），非检索退化。
- **处置**：H2 纪律=列演进不修；真实臂裁决归优化清单 O3。

### FC-06 = BC-D43-002｜HIGH_NOISE_PRECISION 51 格（17 题×3 轮，precision≈0.222）
- **根因**：384 维 local-hashing 无语义，top5 含非 gold 命中。
- **处置**：质量型冻结；O4 词面弱相关新数据集+O3 真实 embedding 臂。

### FC-07 = BC-D43-003｜CLARIFY_EXPECTED_MISSING 6 格（2 题×3 轮）
- **根因**：应答歧义场景在 mock 下不触发澄清分支。
- **处置**：冻结基线；真实臂验证列 O3。

## C. 流程/时序型

### FC-08 = BC-D43-007（本轮以纪律规避复证）｜tombstone 窗口并发污染
- **现象**：D43 重跑 20/20 NO_EVIDENCE——seedfix DELETE 的 lifecycle 瞬态窗口与评测并发。
- **根因**：DELETE tombstone 使检索面短时为空（评测读到瞬态）。
- **处置**：本轮纪律=**评测与 seeding 错峰 + disabled 实例窗口**（D43 规则继承，实测零复发）。
- **教训**：瞬态窗口必须进操作序列设计，不能靠 sleep 赌概率。

### FC-09 = C09 断言口径错（本轮自省案例）
- **现象**：refusal v1 跑 4/4 FAIL。
- **根因**：①误设"REFUSE 须 evidenceCount=0"——实际"检索有证据但不可回答→拒绝"是 D34 合法语义；②R4 对照题在 enabled 矩阵窗被表格夹具干扰（假信号）；③X7-sync 对照用 userA 撞 ADMIN-only 面误判 FAIL。
- **处置**：v2 口径=decision∈{REFUSE,CLARIFY}+sources=0+无[S]标记+文案非空；3 hard PASS+R4 降级 OBSERVATION。
- **教训**：**测试红先定界断言口径，再疑产品**——本轮三处"缺陷"全是探针错。

### FC-10 = OBS-D44-X2｜脚本迁移三坑（工具链）
- **现象/根因**：①prompt scene='SERVICE' 已非法（枚举演进 8 值）；②chat POST=既有会话追加（自定义 sid 4001）；③AddRow 路径在解析期展开致 prep 冻结参数失效（重跑 DB 漂移）。
- **处置**：PLACEHOLDER-TID/SIDA 循环内替换+活取；脚本全部实跑验证。
- **教训**：跨代脚本迁移**必须小步实跑**，`-creplace` 只保证字符正确不保证语义契约同步。

## D. 资产/环境型

### FC-11 = BC-D43-006｜共享 PG 向量表资产污染（diff=39 事件）
- **现象**：D43 首跑质量列 diff=39——E2E 夹具 6 行挤占 top5+null id。
- **根因**：评测与夹具共库无隔离。
- **处置**：E2EClean+删残留重跑 diff=0；本轮 QC-OBS-1（测试库隔离）进优化清单 O2（P1 优先级）。
- **证据**：D43 run-1757 三段定界 / 本轮 cleanup 全链。

### FC-12 = OBS-D43-X1/X2（已消灭）｜外部 IDEA 实例干扰
- **现象**：18080 前代实例两度清共享表干扰资产。
- **处置**：本轮复核 PORT18080=DEAD（议题消灭）；迭代窗口停机决策留用户（H8 不代杀）。

### FC-13 = OBS-D44-X3/X4｜删除面契约边界（本轮新增）
- **现象**：①DELETE /api/v1/documents/{id}=405→5000 包装（端点不存在）；②vi-delete 闭环不清 knowledge_document 登记行与 sync 历史行（demo S1 见 19）。
- **根因**：删除能力唯一通道在 vector-index 域；闭环范围=向量+表格+状态，登记域按设计保留（审计留痕语义）。
- **处置**：文档澄清（api-inventory E17/E08 注记）；demo S0/S9 DB 级自清通道；O4/O10 优化项。

## E. 误报排除（保留防再犯）

### FC-14 = BC-D43-008｜M-D13-G2 4001→200 幂等差异
- **根因**：pt-c 墓碑模板二次删除=资产态不同，非代码缺陷；本轮矩阵轮 2 首跑资产名冲突（ptc→ptc2 改名）即同类现象复现，3 分钟定界。

### FC-15 = BC-D43-012｜19 冻结集 MISSING 误报
- **根因**：probe 脚本 CONCAT 补零不齐；本轮评测 baseline.csv 全列 diff=0 反证冻结面完好。

## 统计与索引

| 域 | 条数 | 阻断已修 | 质量冻结 | 流程 | 资产/环境 | 误报 |
|---|---|---|---|---|---|---|
| 解析域 | 2 | FC-03 | — | — | — | — |
| 检索域 | 2 | — | FC-06 | FC-08 | FC-11 | — |
| 生成域 | 3 | — | FC-05/07 | FC-09 | — | — |
| 权限域 | 3 | FC-02 | — | FC-09③ | FC-13 | FC-14 |
| 同步元数据 | 2 | FC-04 | — | FC-08 | FC-13② | — |
| 资产·环境·工具 | 5 | FC-01 | — | FC-10 | FC-12 | FC-15 |

UNATTRIBUTED=0。全部证据路径可回溯：`docs\evidence\D43\{fixes,evaluation}`、`docs\evidence\D44\{fixes,matrix,verify,cleanup,evaluation,demo}`、`docs\evidence\D42\iteration_records`、D41 整改记录。
