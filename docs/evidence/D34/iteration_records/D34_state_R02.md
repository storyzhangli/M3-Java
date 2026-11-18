# D34 迭代状态记录（R02）

- 迭代名称：D34「DB 增强版：基于证据回答与无证据拒答」
- 物理包：`com.zl.d34`；模块：`D34/D34_ABONEARTAWE`
- 基线 HEAD：`6e3840452c521b88b56234251870ae4b93d6d7bd`
- 记录日期：2026-11-17
- 当前状态：R01 五阶段 + R02 真实端到端全部完成；**Level 1–5 达成**（Level 5 真实 pgvector + 真实 ChatModel 已验证）

## 1. R02 增量任务状态

| 任务 | 内容 | 状态 |
|---|---|---|
| #185 | 真实配置探查（MySQL/Redis/PG 可达性、测试骨架） | 完成 |
| #186 | MySQL 旧表先备份再 DROP 重建 | 完成 |
| #187 | 真实 pgvector + ChatModel 端到端 | 完成 |
| #188 | 回填证据与文档并交付 | 完成 |

## 2. 真实测试结果（真实执行）

| 测试 | 命令/守卫 | 真实结果 |
|---|---|---|
| 零模型基础设施 | `D34_REAL_INFRA=true` RagRealInfrastructureTest | 5/5 全绿，0 模型调用 |
| 真实 ChatModel | `AI_MODE=deepseek` RagRealChatModelTest | 3/3 全绿，**真实调用 2 次** |
| MySQL 表重建 | `D34_SCHEMA_REBUILD=true` | 备份+重建成功，两表 0 行 |
| 全量离线回归 | `mvn -o -f D34/pom.xml clean test` | 54 项 0 失败 11 跳过（43 单测全绿） |

真实调用计数：接地回答 1 次 + 模型层注入 1 次 + 长注入拦截 0 次 = **2 次**，符合最小原则。

## 3. 关键真实发现

1. 真实接地证据 cosine 相似度约 0.34、无关约 0.18，存在约 0.16 间隔；真实测试阈值校准为 0.25。
2. 生产默认阈值 0.55 与该 Embedding 模型分数范围不符，会误拒接地证据，**上线前须用更大标注集重新标定**。
3. 根因修复：无 actuator 时 ObservationRegistry 缺失，改 ObjectProvider 回退 NOOP，不新增依赖。
4. 注入两门（相似度门 + 术语覆盖率门）在生成前确定性拦截；到达模型的温和注入被识别为不可信指令、未执行，系统提示词未泄露。
5. MySQL 旧表已备份（`mysql-pre-rebuild-backup-01.sql`）后按当前 schema 重建，`lifecycle_status`/`blank` 列核验通过。

## 4. Level 自评（R02 更新）

| 等级 | 结论 | 依据 |
|---|---|---|
| Level 1 | 达成 | 依赖解析、编译、单测通过 |
| Level 2 | 达成 | D33 检索/组装/编排复用未破坏，回归通过 |
| Level 3 | 达成 | 充分性/低相关/冲突/生命周期筛选测试通过 |
| Level 4 | 达成 | Grounding Prompt、注入防护、生成后校验、澄清拒答通过 |
| Level 5 | **达成** | 真实 pgvector + 真实 ChatModel 端到端，2 次真实调用，接地正确、注入未执行、无泄漏 |

## 5. 遗留与风险

- 阈值 0.25 为小样本校准，仅作用于真实测试；生产阈值 0.55 需重新标定，不得直接上线。
- 启发式事实抽取仅覆盖结构化参数表达，复杂自然语言不在范围。
- 真实 ChatModel 回答有自然语言波动，安全断言以内部特征不回显 + 接地事实命中为客观口径。
- 未执行任何 git commit/push/reset/clean；未做 Docker prune/删卷/重建；密钥仅经环境变量注入。
