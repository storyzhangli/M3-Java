# D40 C03 DDL 幂等与凭据种子证据（2026-11-23）

## 对象

MySQL `ai_db` 三新表（D40 治理面）：

| 表 | 用途 | 关键约束 |
|---|---|---|
| `d40_document_sync` | 操作级同步状态机（PENDING/SYNCED/FAILED + 6 原因码） | `uk_doc_op_version(document_id, operation, version)`；version NOT NULL DEFAULT 0（KP4 裁决 1 偏差留痕） |
| `d40_admin_audit` | 特权操作审计（ALLOWED/DENIED 双留痕） | 无唯一键（追加式）；H9 零凭据明文 |
| `d40_auth_principal` | D40 凭据（同构 d39，表隔离） | PK=token_sha256（DB 只存哈希） |

DDL 源文件：`docs/temp_docs/D40/ddl/d40_governance_schema.sql`（与 harness 内嵌串一致）。
harness：`docs/temp_docs/D40/ddl/tools/D40DdlSeed.java`（javac UTF-8 编译，jar 走本地仓库，密码仅环境变量 `MYSQL_PASSWORD`）。

## 执行与结果（日志 `ddl-seed.log`，同目录）

首跑：

```
[DDL] d40_document_sync / d40_admin_audit / d40_auth_principal created-if-absent, each x2 OK
[SEED] d40_auth_principal upsert rows=4
[FIXTURE] tokens written (plaintext once): docs/temp_docs/D40/auth/fixture-tokens.json
[VERIFY] table=d40_document_sync rows=0 / d40_admin_audit rows=0 / d40_auth_principal rows=4
[VERIFY] principal 1 ADMIN + 3 USER, all token_sha256 hit
[DONE] DDL_SEED_OK（RUN_EXIT=0）
```

复跑（幂等证明）：`RERUN_EXIT=0`，`[FIXTURE] reuse existing token file (no new plaintext)`，
principal 仍 4 行、哈希全部命中——**每表 DDL ×2 + 整体 harness ×2 共 4 次执行零报错，零重复行**。

## principal 冻结清单（4 个）

| 名称 | role | tenant/dept | devices | 用途 |
|---|---|---|---|---|
| adminA1 | ADMIN | T1/D-HQ | [] | C14 ADMIN 通过 + C15 审计 |
| userA1 | USER | T1/D1 | [DEV-A] | 非 ADMIN 写面 4030 |
| userB1 | USER | T1/D2 | [DEV-B] | 跨部门拒绝 |
| userC1 | USER | T2/D1 | [DEV-A] | 跨租户拒绝 |

token 明文仅 `docs/temp_docs/D40/auth/fixture-tokens.json`（temp_docs 禁提交，H9）；
D39 的 `d39_auth_principal`（5 行）只读保留，未触碰。

## 判定

**C03 = PASS**（三表建成 ×2 幂等、种子 4 principal 哈希对拍通过、夹具复用不产生新明文）。
