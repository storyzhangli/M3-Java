# D30 证据 07：正式向量表 Schema 独立取证（384 基线，整改后）

> 取证方式：经 SSH 调用容器内 psql，独立于 JVM 集成测试的回读路径。
> 临时脚本：`docs/temp_docs/D30/formal_schema_check_384.py`；原始输出：`formal_schema_out_384.md`。
> 迁移历史：原 `vector(256)` 空表（0 行）已按整改要求删除并重建为 `vector(384)`，详见证据 12。

## 一、正式表 psql 元信息（真实输出）

```text
                   Table "public.d30_vector_store"
  Column   |    Type     | Collation | Nullable |      Default
-----------+-------------+-----------+----------+--------------------
 id        | uuid        |           | not null | uuid_generate_v4()
 content   | text        |           |          |
 metadata  | json        |           |          |
 embedding | vector(384) |           |          |
Indexes:
    "d30_vector_store_pkey" PRIMARY KEY, btree (id)
    "d30_vector_store_index" hnsw (embedding vector_cosine_ops)
```

## 二、catalog 权威类型（真实输出）

```text
 schema |      table       |  column   |  data_type
--------+------------------+-----------+-------------
 public | d30_vector_store | id        | uuid
 public | d30_vector_store | content   | text
 public | d30_vector_store | metadata  | json
 public | d30_vector_store | embedding | vector(384)
```

`pg_attribute.format_type` 权威确认 embedding 列就是 **vector(384)**，与 384 维度基线一致。

## 三、HNSW 索引定义（真实输出）

```text
 d30_vector_store_pkey  | CREATE UNIQUE INDEX ... USING btree (id)
 d30_vector_store_index | CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)
```

- 主键索引：btree(id)；
- 向量索引：**HNSW**，操作类 `vector_cosine_ops`，与余弦距离 `<=>` 口径匹配。

## 四、关于内置 vectorTableValidations 的取舍

`PgVectorStore.afterPropertiesSet` 的真实执行顺序（经字节码反编译确认）为：

1. 先 `validateTableSchema`（若 `vectorTableValidationsEnabled=true`）；
2. 后 `initializeSchema`（建扩展、建表、建索引）。

全新库下表尚不存在，若开启校验会在建表前即报 `Table ... does not exist`。因此本轮采用 Builder 默认
（校验关闭），改为通过本证据的独立 catalog 回读核验表结构——证据更直接、可追溯，且不依赖框架内部顺序。

## 五、行数确认

取证时 `row_count = 0`（集成测试与 384 HTTP 演示数据均已按标记清理，未残留）。

## 六、结论

正式表 `public.d30_vector_store` 结构经独立路径真实回读：id uuid、content text、metadata json、
embedding **vector(384)**，并具备 HNSW（vector_cosine_ops）索引；表结构与 384 基线、余弦口径完全一致。
