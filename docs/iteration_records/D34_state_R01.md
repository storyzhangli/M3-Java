# D34 迭代状态记录（R01）

- 迭代名称：D34「DB 增强版：基于证据回答与无证据拒答」
- 物理包：`com.zl.d34`；模块：`D34/D34_ABONEARTAWE`
- 基线 HEAD：`6e3840452c521b88b56234251870ae4b93d6d7bd`
- 记录日期：2026-11-17
- 当前状态：阶段一至阶段五完成；Level 4 达成（受控口径），Level 5 阻塞待真实 ChatModel 凭据

## 1. 五阶段执行状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| 阶段一 | 脚本化环境探测（`probe_d34.ps1` → `00-env-probe.txt`） | 完成 |
| 阶段二 | 精读关联类与设计（`01-design-and-change-list.md`） | 完成 |
| 阶段三 | 增量实现证据评估与多层安全（11 新类 + 10 改类最小改动） | 完成，编译通过 |
| 阶段四 | 测试与拒答评测集（43 用例 + 12 用例评测集 + 7 指标） | 完成 |
| 阶段五 | 证据索引、迭代记录、两份正式文档 | 完成 |

## 2. 本迭代新增能力

1. 文档生命周期：`DRAFT/ACTIVE/DEPRECATED`，评估先做生命周期过滤。
2. 证据评估：充分/低相关/冲突，建议动作 `ANSWER/CLARIFY/REFUSE/CONFLICT`，结果含 9 字段。
3. 受控拒答与澄清：无证据/低相关/覆盖不足拒答，型号歧义复用 D18 `ClarificationService` 澄清。
4. 冲突受控：同主体同参数不同值判 CONFLICT，不同设备型号不判冲突，不按版本号一刀切。
5. 多层安全：UNTRUSTED_EVIDENCE 边界 + Prompt 注入防护 + 生成后校验（引用合法性与事实支持分离）。

## 3. 验证结果（真实执行）

- 命令：`mvn -o -f D34/pom.xml clean test`
- 结果：`Tests run: 43, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
- 日志：`logs/D34/mvn-test-D34-R01.txt`（UTF-8）
- 评测集：`docs/evidence/D34/refusal-evaluation-dataset.json`，12/12 通过
- 指标摘要：无证据拒答准确率 100%、低相关误答率 0%、过度拒答率 0%、冲突误答率 0%、注入成功率 0%、引用合法率 100%、整体通过率 100%

## 4. Level 自评（未达不宣称）

| 等级 | 结论 | 依据 |
|---|---|---|
| Level 1 | 达成 | 依赖解析、编译、单测通过 |
| Level 2 | 达成 | D33 检索/组装/编排复用未破坏，回归通过 |
| Level 3 | 达成 | 充分性/低相关/冲突/生命周期筛选测试通过 |
| Level 4 | 达成 | Grounding Prompt、注入防护、生成后校验、澄清拒答通过 |
| Level 5 | 阻塞 | 真实 ChatModel 凭据缺失；PG 凭据已具备，Mock 不替代真实端到端 |

## 5. 遗留与风险

- Level 5：需 `AI_MODE=deepseek` + `AI_API_KEY` 完成真实 pgvector + 真实 ChatModel 四类风险场景。
- MySQL 旧表 `knowledge_document/knowledge_document_page`（0 行）与当前 schema 冲突，DROP/ALTER/不动三选项待用户拍板；确认前不做破坏性 DROP。
- 启发式事实抽取仅覆盖结构化参数表达，复杂自然语言不在范围，已在文档如实说明。
- 未执行任何 git commit/push/reset/clean；未做 Docker prune/删卷/重建等破坏性操作。
