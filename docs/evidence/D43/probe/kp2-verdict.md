# KP2 冻结件 —— D43 存储盘点 + 幂等 DDL + seeding + 双锚

冻结时刻：2026-11-26（P0 段，先于一切 E2E/越权/评估执行）
口径来源：任务书 §3 KP2；比较锚=《D42 rerank-protocol.json corpus 节》（docs\evidence\D42\probe\rerank-protocol.json）

## 1. MySQL d43_ 五表 DDL（KP2-a）

- 模板：docs\temp_docs\D42\ddl\d42_schema.sql 全文逐列同构改编（d42_→d43_ 前缀；键名/ENUM/JSON 列/uk 全镜像）。第一版自造列稿已废弃重做（违反"不得把猜测当框架事实"纪律，自查纠偏）。
- 执行：D43DdlSeed.java（D42 工具 .Replace 链改编）× 2 次。
- 证据：docs\evidence\D43\ddl\ddl-run1.txt / ddl-run2.txt。
- 结果：run1 五表建成（[DDL] executed=5 statements）；run2 重跑零报错、行数零变化（五表 rows=0）→ **幂等达成**。
- 表清单：d43_document_sync / d43_admin_audit / d43_auth_principal / d43_table_extract / d43_table_row。
- 存在性复核：D43Kp2Extra（information_schema，LOWER 匹配）D43_TABLES=5。注：直接表名 LIKE 'd43\_%' 在该实例（MySQL 9.7.0-commercial, lower_case_table_names=1）返回 N/A(42000)，属盘点工具查询路径问题，与建表结果无关，已用 information_schema 通道补核。

## 2. PG 向量表（KP2-b）

- d43_vector_store：DDL 前 TABLE_EXISTS=true / ROWS=0 / FINGERPRINT=e3b0c442…（空集 SHA-256 常量，EMPTY_SHA256 自证一致）。
- 驱动教训：postgresql-42.1.4 对 PG 18.7 SCRAM-SHA-256 报"不支持 10 认证类型"（死路，已留痕废弃）；**权威驱动=42.7.11**（后续 harness 一律使用）。
- seeding 后：ROWS=8（3+3+2，与语料 equipment-fault-corpus.json 8 chunks 一致）。

## 3. seeding（KP2-c）

- 脚本：docs\temp_docs\D43\d43_index-corpus.ps1（D42 版改编：D42→D43 / d42_vector_store→d43_vector_store / 模块目录 D43\D43_RAG_ETEPAR；管线=register 核验→prestate→DELETE→STRUCTURE 重建→poststate）。
- 实例：docs\temp_docs\D43\d43_instance.ps1（AI_MODE=mock；PGVECTOR_DB_PASSWORD 仅从 User 级环境变量转注；清理 8 个实验开关 env 保证 baseline 态）。
- 结果：3 文档 REGISTER-OK（COMPLETED v1）→ DELETE removed=3/3/2 code=200 → INDEX COMPLETED（chunkCount 3/3/2）→ VERIFY 3/3/2。
- 证据：kp2-seed-prestate.json / kp2-seed-results.json / kp2-seed-poststate.json。
- d43_document_sync：seeding 后 3 行（3 文档各 1，正常态）。

## 4. 双锚裁决（KP2-d，本件核心）

| 面 | D43 实测 | 前代锚 | 裁决 |
|---|---|---|---|
| ID 面（全行含 chunkId，Collections.sort→joinNL） | **50c457b6762afd516a2c1d3f8f377d52001ef0ee3dd6ab3f5cdee83fab43efd1** | D42=2cf619bd… / 前前代=10fc69d5… | **≠（预期内）**：StableChunkIdGenerator.NAMESPACE 尾段 …6d43（D42=…6d42，D41=…6d41）模块隔离设计，源码 L28 实证。D-EX2 同构先例。 |
| 内容面（canonical 去首列 chunkId 后**重排**→joinNL） | **c377bb2f62d7b71b4dfa3f87d47722b7bcdc989aaf4b218c607f8e7a9b6d64fb** | c377bb2f…（历代锚） | **= 逐字节等价**。语料无漂移，CORPUS_DRIFT（熔断#2）语义不成立。 |

### 内容面口径钉死过程（D43-02 文档素材，教训级）

1. 首跑 D43 probe 内容面输出 5522ae49 ≠ c377bb2f，一度构成漂移警报。
2. 行级证据先行：D42/D43 postseed CANON 行「去首列后」Compare-Object **零 diff** → 内容与顺序均一致 → 指向口径差异而非数据差异。
3. 追溯锚计算源：D42VectorProbe.java 无内容面输出、全 docs 无复算留痕——c377bb2f 在 D42 时代是**脚本外一次性计算**（协议只记结果）。
4. 口径×置换复算矩阵（8 变体）命中唯一：**去首列后 Collections.sort 再 join("\n")** = c377bb2f（PowerShell 与 Java D43FpCheck 双实现独立复算一致）。
5. 根因：probe 增列 v1 沿用「全行（含 chunkId）排序后直接剥列」=49e61733，chunkId 代际序≠内容字典序。修复=剥列后重排（源码注释已锚定本实证）。
6. 纪律结论：**跨代锚必须"结果+口径"双留痕**；只有哈希值没有可复算定义的锚，在下一代就是不可证伪的悬案。本口径全文写入 e2e-protocol.json。

## 5. 遗留基线冻结（H6 零触碰清单，seeding 后复核不变）

- knowledge_document_total=18（骨架 D43 期间实测；D42 时代=10，差异= D43 骨架既有行数，盘点实证非本迭代产生）
- legacy DOC-* 3 行（DOC-CNC-COOLING-001=3 / DOC-SERVO-TROUBLE-002=3 / DOC-WORKORDER-SPEC-003=2 chunk 归属；d43 轨复用同 documentId 走 DELETE→重建管线，MySQL 遗留 chunk 行未触碰）
- document_chunk_index_total=32、chunk_orphan_rows=24、document_vector_index_total=7
- 以上 D43 全程零删改；C18 清理核验以此为回归锚。

## 6. KP2 通过判据（预注册，后续不可放宽）

- KP2-PASS 条件：DDL×2 幂等 + seeding VERIFY 3/3/2 + 内容面=c377bb2f + ID 面冻结=50c457b6 → **全部满足，KP2=PASS**。
- d43_vector_store 内容变化（除 D43 自身 E2E 生命周期临时变更外）即视 CORPUS_DRIFT 熔断。
- hybrid 轨（D38 遗产）expected 锚默认值仍指向前代 10fc69d5——D43 主链验证若需启用 hybrid，必须 env HYBRID_EXPECTED_FINGERPRINT=50c457b6…（本表 ID 面）注入；R01 默认不开 hybrid（继承 D42 纪律）。
