# D30 证据 05：pgvector 扩展与版本取证

> 取证方式：查询 `pg_extension`、`pg_available_extensions` 与 PostgreSQL 版本，原始记录见 `logs/D30/03`。

## 一、PostgreSQL 版本

```text
PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) on x86_64-pc-linux-gnu,
compiled by gcc (Debian 12.2.0-14+deb12u1) 12.2.0, 64-bit
```

## 二、已安装扩展版本

命令：`SELECT extname, extversion FROM pg_extension WHERE extname='vector';`

```text
 extname | extversion
---------+------------
 vector  | 0.8.7
(1 row)
```

## 三、可用扩展版本

命令：`SELECT name, default_version, installed_version FROM pg_available_extensions WHERE name='vector';`

```text
  name  | default_version | installed_version
--------+-----------------+-------------------
 vector | 0.8.7           | 0.8.7
```

`pg_extension` 与 `pg_available_extensions` 两处版本均为 **0.8.7**，互相印证：扩展已真实安装且处于可用状态。

## 四、pgvector 核心能力实测（独立测试表）

在 `logs/D30/04` 中，使用独立表 `d30_vector_test`（`VECTOR(3)`，不覆盖正式表）实测：

1. 真实插入 3 条向量；
2. 余弦距离 `<=>` 排序数学正确：

```text
A[1,0,0] 自身距离      = 0
B[0.9,0.1,0] 距离      = 0.00611625
C[0,1,0] 正交距离      = 1
```

3. 建立 HNSW 索引 `idx_d30_embedding_cosine`（`vector_cosine_ops`）；
4. 错误维度（4 维）写入被拒：`ERROR: expected 3 dimensions, not 4`（退出码 3，fail-closed）。

## 五、HNSW 执行计划（logs/D30/05）

- 默认仅 3 行，优化器选择 `Seq Scan + Sort`（成本驱动的正确选择，非失败）；
- `SET enable_seqscan=off` 后出现：

```text
Index Scan using idx_d30_embedding_cosine ... Order By (embedding <=> ...)
```

证明 HNSW 索引对余弦算子 `<=>` 真实可用。

## 六、结论

pgvector 扩展 0.8.7 已在 PostgreSQL 18.7 上真实安装并验证：向量类型、余弦距离排序、HNSW 索引、
维度强制约束均有真实执行证据；扩展不可用或维度不符的情况在本环境不存在，且验证机制为 fail-closed。
