# D30 pgvector SQL 能力取证

## 一、基础能力（建表/写入/余弦距离/HNSW）

```text
CREATE EXTENSION
DROP TABLE
CREATE TABLE
INSERT 0 3
 id |  content   |   cosine_distance    
----+------------+----------------------
  1 | 测试向量 A |                    0
  2 | 测试向量 B | 0.006116251198662548
  3 | 测试向量 C |                    1
(3 rows)

CREATE INDEX
        indexname         
--------------------------
 d30_vector_test_pkey
 idx_d30_embedding_cosine
(2 rows)
[stderr]
NOTICE:  extension "vector" already exists, skipping
NOTICE:  table "d30_vector_test" does not exist, skipping
退出码: 0
```

## 二、维度不匹配写入（预期失败，fail-closed）

```text
ERROR:  expected 3 dimensions, not 4
退出码: 3（非 0 为预期，证明维度被强制约束）
```