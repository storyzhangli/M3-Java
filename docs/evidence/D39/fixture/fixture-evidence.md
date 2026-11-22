# D39-R01 P4 权限夹具注入证据（#238）

- 时间：2026-11-22；实例：新代码（P2/P3 后，308 源，task 2d0c9611，mock，access enabled=false 写入态）
- 脚本：`docs\temp_docs\D39\d39_fixture-access.ps1`（已执行，6×[REGISTER code=200]+6×[INDEX COMPLETED]+6×[VERIFY chunks=2]）
- harness：`docs\temp_docs\D39\fixture\tools\D39FixtureFingerprint.java`（只读，零写入零 DDL）

## 冻结结果

- PG 总行数：`ROWS=20`（8 冻结未打标 + 12 夹具打标）
- **夹具态指纹（D5）**：`388a593ca72d32d72a8c7a9b45e596393385f1aeed937f725be2bc0587088a48`
  - 注入方式：T 矩阵启动 `HYBRID_EXPECTED_FINGERPRINT=<该值>` 环境变量（不落 yml，不落 Git）
  - 清理后应恢复冻结值 `10fc69d5…`（C19 核验项）
- 证据：`d5-fingerprint.txt`（20 行明细）、`fixture-manifest.json`（文档→chunkIds×三键）、`fixture-results.json`

## 权限矩阵夹具（chunkId 详见 manifest）

| 文档 | tenant/dept/device | 片段 | 设计可见主体 |
|---|---|---|---|
| DOC-D39-F1 | T1/D1/DEV-A | 2 | userA、adminT1 |
| DOC-D39-F2 | T1/D1/* | 2 | userA、adminT1 |
| DOC-D39-F3 | T1/D2/DEV-B | 2 | userB、adminT1 |
| DOC-D39-F4 | T1/*/* | 2 | userA、userB、adminT1（部门/设备通配） |
| DOC-D39-F5 | T2/D1/* | 2 | userC（跨租户隔离判别器） |
| DOC-D39-F6 | T1/D2/* | 2（含 D2P-771） | userB、adminT1；**userA 不可见**（dept 隔离判别器） |
| 冻结语料×3 | null/null/null | 8 | 无人（D3 fail-closed 判别器） |

- 判别器设计：F6 片段 0 内嵌锚点问句字面原文「数控机床出现主轴过热报警，应该先检查什么？」——
  无权限过滤时相似度最高；权限过滤正确时 userA 的该问句结果集必须不含 F6 chunkId（跨部门泄露即 FAIL）。
- F 系列 chunkId 均为 UUIDv5 确定性生成（splitVersion=split-v1），句式复用设备故障域、
  逐字不复制冻结语料（H6）；夹具文档 source=fixture-access 独立可清理。
- token 明文仅在 `docs\temp_docs\D39\auth\fixture-tokens.json`（H9，禁提交）。

## 遗留到 T 矩阵的断言基线

- 全部检索断言基于返回 chunkId/documentId 集合（H8），不解析文案。
- 越权命中熔断：任一测试出现权限外 ID → 停止全矩阵重跑（熔断#1）。
