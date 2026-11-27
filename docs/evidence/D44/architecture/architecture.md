# D44 架构图（答辩四件套·架构说明）

> Typora 0.11.18 兼容：graph TD / subgraph ID [标题] / 节点双引号；节点与连线全部对应源码实扫（20 控制器、57 端点、六域包结构 com.zl.d44.*）。

## 1. RAG 端到端全景（主链+双存储+权限+同步）

```mermaid
graph TD
    subgraph ENTRY ["接入层 (20 Controller / 57 端点)"]
        RC["RagController /rag/ask"]
        SSC["SimilaritySearchController /retrieval/search"]
        DOC["DocumentController /documents"]
        VIC["VectorIndexController /vector-index"]
        SYNCCTL["DxxSyncController /sync"]
        TC["TableController /tables"]
        PT["PromptTemplateController /prompts"]
        HYB["Hybrid*Controller (D38 混合轨)"]
        ONE["One*Controller (百炼隔离轨)"]
        GATE["AccessGateFilter + guardAdmin (22 ADMIN 端点)"]
    end

    subgraph ORCH ["编排层"]
        RO["RagOrchestrator (D42 rerank 挂点, 默认 noop)"]
        DEC{"决策判定"}
        ANSWER["ANSWER 带引用[S]"]
        REFUSE["REFUSE 零伪造引用"]
        CLARIFY["CLARIFY 追问"]
    end

    subgraph RETR ["检索与权限域 (D39)"]
        FILT["元数据前置过滤 (tenant/dept/device 三元组)"]
        VS["VectorStore 抽象"]
        RERANK["RerankStrategy (enabled=false)"]
    end

    subgraph STORE ["存储层"]
        PG[("PG spring_ai<br/>d44_vector_store (8)")]
        PGO[("one_d44_vector_store (0)")]
        MYSQL[("MySQL ai_db<br/>knowledge_document (18)")]
        CIDX[("document_chunk_index 账本")]
        TBL[("d44_table_extract/row")]
        SYNC[("d44_document_sync 状态机")]
        AUD[("d44_admin_audit")]
        PRN[("d44_auth_principal")]
    end

    subgraph PIPE ["入库管线 (D26-D31)"]
        REG["register 登记"]
        IMP["import 解析 (PDF/Word/MD D27 + 清洗切分 D28)"]
        EMB["Embedding 384 local-hashing (D29)"]
        IDXN["index 同事务替换 (D31 幂等)"]
    end

    RC --> RO
    RO --> DEC
    DEC --> ANSWER
    DEC --> REFUSE
    DEC --> CLARIFY
    RO --> SSC
    SSC --> FILT
    FILT --> VS
    VS --> PG
    VS --> RERANK
    RERANK --> PG
    ONE --> PGO
    HYB --> PG
    GATE --> ENTRY
    DOC --> REG
    REG --> IMP
    IMP --> EMB
    EMB --> IDXN
    IDXN --> PG
    IDXN --> CIDX
    IMP --> TBL
    VIC --> SYNC
    SYNC --> SYNC
    VIC --> IDXN
    VIC --> RECON["reconcile 三轨对账"]
    RECON --> PG
    RECON --> CIDX
    RECON --> TBL
    GATE --> PRN
    GATE --> AUD
    TC --> TBL
    PT --> MYSQL
```

## 2. 六域分解图（生命周期×权限×同步）

```mermaid
graph LR
    subgraph D_A ["A 导入域"]
        A1["register"] --> A2["import 解析"] --> A3["chunk 切分"]
    end
    subgraph D_B ["B 索引域"]
        B1["embedding"] --> B2["PG upsert"] --> B3["chunk 账本"]
    end
    subgraph D_C ["C 检索域"]
        C1["元数据过滤"] --> C2["余弦 Top-K"] --> C3["可选 rerank/noop"]
    end
    subgraph D_D ["D 生成域"]
        D1["证据判定"] --> D2["ANSWER/REFUSE/CLARIFY"]
    end
    subgraph D_E ["E 同步域"]
        E1["UPDATE_CLEANUP"] --> E2["DELETE 闭环"] --> E3["retry 状态机"]
    end
    subgraph D_F ["F 治理域"]
        F1["principal 鉴权"] --> F2["admin 审计双分支"] --> F3["reconcile 三轨"]
    end
    D_A --> D_B --> D_C --> D_D
    D_B --> D_E
    D_E --> D_F
    D_C --> D_F
```

## 3. 权限代数（enabled 态，C08 实证 335 格 LEAK=0）

```mermaid
graph TD
    T["请求"] --> Q{"access.enabled?"}
    Q -- "false (H7)" --> PASS["全通·零门禁 (25 项 disabled 扫描=0 差异)"]
    Q -- "true" --> TOK{"Bearer token 有效?"}
    TOK -- "无/伪造/过期" --> R4030["4030 统一拒绝"]
    TOK -- "valid" --> IDN["principal 解析 (tenant/dept/device/role)"]
    IDN --> ADM{"ADMIN 端点?"}
    ADM -- "是且 role≠ADMIN" --> DEN["4030 + audit DENIED"]
    ADM -- "是且 ADMIN" --> OK1["ALLOWED + audit"]
    ADM -- "否" --> FLT["检索元数据前置过滤"]
    FLT --> LEAK{"跨租户/越权文档?"}
    LEAK -- "命中" --> HIDE["不可见 (矩阵 335 格 0 泄漏)"]
    LEAK -- "通过" --> RESP["正常响应"]
```

## 4. 同步状态机（d44_document_sync，KP4 冻结口径）

```mermaid
graph TD
    NONE["无操作"] --> DEL["DELETE / ver=0"]
    NONE --> UC["UPDATE_CLEANUP / ver>0"]
    DEL --> P["PENDING"]
    UC --> P
    P --> |"worker 领取"| S["SYNCED"]
    P --> |"异常"| F["FAILED"]
    F --> |"attempt<max 退避"| P
    F --> |"耗尽"| RX["RETRY_EXHAUSTED (reason_code)"]
    RX --> |"POST /sync/retry 重置 attempt=0"| P
    S --> AUD["admin_audit ALLOWED 留痕"]
```
