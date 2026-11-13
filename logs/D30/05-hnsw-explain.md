# D30 HNSW 执行计划取证

```text
                                 QUERY PLAN                                 
----------------------------------------------------------------------------
 Limit  (cost=1.06..1.07 rows=3 width=48)
   ->  Sort  (cost=1.06..1.07 rows=3 width=48)
         Sort Key: ((embedding <=> '[1,0,0]'::vector))
         ->  Seq Scan on d30_vector_test  (cost=0.00..1.04 rows=3 width=48)
(4 rows)

SET
                                               QUERY PLAN                                               
--------------------------------------------------------------------------------------------------------
 Limit  (cost=8.02..12.06 rows=3 width=48)
   ->  Index Scan using idx_d30_embedding_cosine on d30_vector_test  (cost=8.02..12.06 rows=3 width=48)
         Order By: (embedding <=> '[1,0,0]'::vector)
(3 rows)

RESET
退出码: 0
```