# D39_state_R01 —— 知识权限与元数据过滤
- 迭代/切片：D39 / R01；基线 commit：da180ef（执行时 HEAD 复核一致）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17
- 冻结参数：predicateScheme=tenant eq ∧ GROUP(OR dept eq/eq"*") ∧ GROUP(IN device∪"*")（ADMIN=tenant eq）wildcardSentinel="*" principalTable=d39_auth_principal baselineFingerprint=10fc69d5c9c3fd088b98578d74278135b1c04b7cd8b29b361b4c983926567af9 fixtureFingerprint=388a593ca72d32d72a8c7a9b45e596393385f1aeed937f725be2bc0587088a48 datasetHash=75cc8d83cfb2… denyCode=4030（probe/access-params.json）
- 偏差裁决：D1 最小服务端凭据 ✓ / D2 三键+哨兵 ✓ / D3 无标签=权限空间外 ✓ / D4 端点分级（实扫 68=18 类+50 方法，任务书 66 为编制口径差异）✓ / D5 指纹耦合（HYBRID_EXPECTED_FINGERPRINT env 注入+清理复验）✓ / D6 缓存裁决（零新增检索缓存）✓
- 实现偏差（相对任务书编制值，均以执行时实读为准）：metadata 列类型=json（非 jsonb，SQL 层缺失键三形态谓词 0 命中实证等价 fail-closed）；`Filter$Expression` record 访问器 type()/left()/right()（字段 private，单测按访问器断言）。

## 门禁与用例落表（四态，合计 20+4 条）
| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| KP1–KP4 | PASS | probe\{endpoint-inventory.csv,kp2-inventory*.json,access-params.json} | KP2 曾发现 d39_vector_store=0 行（骨架未入库）→按熔断#2 先修资产（index-corpus 重建 8 chunks，指纹逐字节一致） |
| C01 | PASS | unittest\{c01-java-tests.md,c01-frontend-tests.txt} | 编译 BUILD SUCCESS（308 源）；Java 单测 29/29（本模块首例）；node --test 6/6 |
| C02 | PASS | probe\ | 参数冻结 access-params.json |
| C03 | PASS | ddl\{seed-run1.txt,seed-run2.txt,kd-columns-x2.txt} | ×2 幂等；首跑 ALTER 断言哨兵 bug 修复重跑留痕；遗留行 NULL 未补值 |
| C04 | PASS | security\t8.jsonl | 无/无效/过期 token=4030 |
| C05 | PASS | regression\{regression-result.json,diff-vs-d37-frozen.txt} | QUALITY_FIELDS_IDENTICAL 60/60；QA-001 top1 同 id delta=0；匿名行为探针零差异 |
| C06 | PASS | metadata\binding-evidence.md + fixture\d5-fingerprint.txt | 三键全链一致 |
| C07 | PASS | enforcement\pushdown-vector.json | topK=20/th=0 越权零命中+在库正证=下推先于 Top-K |
| C08 | PASS | enforcement\pushdown-keyword.json | D2P-771 双路同口径 |
| C09 | PASS | enforcement\fusion-closure.json | 集成闭包断言+纯函数单测 |
| C10 | PASS | enforcement\rag-entry.json | citations ⊆ 许可集；无 token=4030 |
| C11 | PASS | security\t1.jsonl | 5 cases 全绿 |
| C12 | PASS | security\t2.jsonl | body/header 身份声明全忽略 |
| C13 | PASS | security\t3.jsonl+t5.jsonl | admin 租户内全覆盖；跨租户零命中（含 ADMIN） |
| C14 | PASS | security\t6.jsonl | DEV 隔离+通配正证 |
| C15 | PASS | security\c15-c20-hygiene-scan.txt | 检索缓存 grep=0 新增；prompt 缓存既有归属列明 |
| C16 | PASS | security\t8-failclosed.jsonl | MySQL/PG 坏端口双演练=启动 fail-fast；无降级分支静态核验 |
| C17 | PASS | security\t9.jsonl+t10.jsonl | 遗留 fail-closed+注入载荷检索层零越权 |
| C18 | PASS | enforcement\{surface-audit.json,surface-audit-online.jsonl} | 在线 9 断言全绿（embeddings 首轮 5000=脚本 GET/POST 误用，修正复测 4030 留痕） |
| C19 | PASS | cleanup\{cleanup-api.log,cleanup-verify-final.txt} | FIXTURE_REMAINING=0×3；指纹恢复=10fc69d5…；实测补充事实：delete 端点不联动 MySQL knowledge_document 行→DB 级夹具 DELETE 完成 |
| C20 | PASS | security\c15-c20-hygiene-scan.txt + git\（本文件提交记录） | token 明文/sk-/Bearer 实值扫描 0 命中；surefire/target/class/fixture-tokens 不入 Git；提交不 push |
| L5 | NOT RUN | — | 单机环境，如实标注 |

## 分级结论
L1=PASS（C01）L2=PASS（C02–C05）L3=PASS（C06–C18）L4=PASS（C19–C20）L5=NOT RUN（单机）

## 安全测试结论（引用返回 ID 集合断言）
- T1–T10 = 全 PASS（37 cases，FAIL=0，越权命中数恒 0）；下推证明（KP4 字面同句夹具）：userA 极限参数零命中 vs userB/admin raw 必现 ⇒ SQL WHERE 下推于 Top-K 之前，排除内存后过滤假通过。
- 权限矩阵：4 principal ×（6 夹具文档+3 遗留文档）期望/实际一致率 100%（report\permission-matrix.csv + security-test-report.md）。
- 全矩阵按纪律重跑 3 次（首轮 T8/T4 两处均为脚本缺陷非产品缺陷，留痕于报告"FAIL 处置记录"节）；熔断触发=0。

## 成本实耗
DeepSeek calls=0 tokens=0（ai.mode=mock 结构性零远程）；百炼 calls=0（one_ enabled=false）；Token 恒 0（阈值模板保留未触及）；夹具清理 FIXTURE_REMAINING=0。

## 诚实声明
- 已执行并通过：上表全部 PASS 项（每条含实测证据文件）；D37 评测器全量 20 问×3 轮+SelfTest 75/75；Maven compile/test 真实退出码 0；矩阵脚本 3 轮全量执行。
- 已执行但失败（均已修复复验或如实留痕，无带病项）：清理脚本首版 -Apply 未转发 harness 参数（修复重跑）；C05 首跑 STEP4 --diff 参数错位（$baseline 字符串无 .FullName，修复复跑）；矩阵首轮 T4 端点误写/T8 空 headers 绑定/embeddings GET 误用——均测试脚本缺陷，产品断言全绿。
- 尚未执行（建议后续验证）：one_ 轨真实端到端；SSE 流式全路径权限端到端；运行中掉线瞬间行为（以启动 fail-fast+静态核验替代）。
- N/A 项：L5 跨机（单机）；真实生成质量（mock 迭代范围外）；限流/管理后台（R01 范围外）。
- 遗留风险（任务书未点名端点按最小必要未收口，D40 候选）：VectorIndexController 写面（index/indexFromPages/delete/reconcile/recover）、documents register/import/get/list、Embedding model/compatibility、HistoryController 会话列表外的明细端点、PromptTemplate 管理面；健康端点文案仍含"X-Dev-User 开发身份"字样；view.filtered 字段仅业务口径未参与安全断言（H8）。

## 遗留与 D40 候选
one_ 真实权限端到端 / SSE 流式全路径 / JWT 或 Spring Security 升级 / 权限管理后台与写面收口 / 跨机 L5 / 遗留文档生命周期标签迁移方案（当前 D3=权限空间外）。
