# D25 RAG 架构图（目标态与当前态）

> Typora 兼容 Mermaid：`graph TD`、`subgraph`、节点文本双引号包裹，无特殊 HTML。
> 图例：标【已实现】=代码可定位；标【规划】=本轮只定义、未接依赖。

## 1. 目标态 RAG 总体架构（写入侧 + 查询侧）

```mermaid
graph TD
    subgraph WritePath["写入侧 Ingestion（多为规划）"]
        S1["原始资料 MANUAL/GUIDE/SPEC【规划】"]
        S2["接入 Ingestion 去重/哈希【规划】"]
        S3["解析 Parsing 文本+定位【规划】"]
        S4["切分 Chunking 片段+偏移【部分已实现】"]
        S5["元数据 Metadata 版本/权限【本轮定义】"]
        S6["Embedding 向量化【规划】"]
        S7["VectorStore 写入【规划】"]
        S1 --> S2 --> S3 --> S4 --> S5
        S4 --> S6 --> S7
    end

    subgraph ReadPath["查询侧 Retrieval（核心链路）"]
        Q1["用户问题 + 身份上下文"]
        Q2["鉴权 Authorization 先裁决【规划】"]
        Q3["检索 Retrieval 关键词/向量+过滤【部分已实现】"]
        Q4["重排 Rerank【规划】"]
        Q5["上下文组装 Context Assembly【已实现】"]
        Q6["生成 Generation 带引用【已实现】"]
        Q7["引用校验与编号清洗【已实现】"]
        Q1 --> Q2 --> Q3 --> Q4 --> Q5 --> Q6 --> Q7
    end

    S7 --> Q3
    S5 --> Q2
```

## 2. 分层架构与依赖方向

```mermaid
graph TD
    subgraph L1["接口层 Controller"]
        A1["Chat / Stream / Device / History / Prompt 控制器"]
    end
    subgraph L2["应用层 Application Service"]
        A2["KnowledgeChatOrchestrator 编排"]
        A3["RoleConversationService / DeviceChatService"]
    end
    subgraph L3["领域层 Domain"]
        A4["KnowledgeDocument / Chunk 设计模型"]
        A5["CitationRegistry 引用边界"]
        A6["ChatModelPort / FormalTicketPort 端口"]
    end
    subgraph L4["基础设施层 Infrastructure"]
        A7["KnowledgeCorpus + RetrievalService 关键词检索【已实现】"]
        A8["DeepSeek Adapter 模型 SDK【已实现】"]
        A9["VectorStore / Embedding 适配器【规划】"]
        A10["MySQL / Redis / 对象存储【部分已实现】"]
    end
    A1 --> A2
    A1 --> A3
    A2 --> A7
    A2 --> A5
    A2 --> A6
    A3 --> A6
    A5 --> A4
    A6 --> A8
    A6 --> A9
    A7 --> A10
    A8 --> A10
    A9 --> A10
```

## 3. 当前态最小可用链路（真实存在，非向量）

```mermaid
graph TD
    B1["classpath equipment-fault-corpus.json 3文档8片段"]
    B2["KnowledgeCorpus 扁平化为不可变 KnowledgeChunk"]
    B3["KnowledgeRetrievalService bigram+加权 title×3 section×2 excerpt×1"]
    B4["CitationRegistry 分配 S1.. 并清洗自造编号"]
    B5["KnowledgeChatOrchestrator 检索→增强→调用→校验"]
    B6["AiChatService mock/remote 生成"]
    B1 --> B2 --> B3 --> B4 --> B5 --> B6
```

## 4. 关键约束（图对应规则）

- 依赖方向只能自上而下：Controller → 应用 → 领域 → 基础设施；供应商 SDK/向量对象/连接串/本机路径不进入领域与 DTO。
- 鉴权在证据暴露前完成；Chunk 强制继承父文档权限、默认拒绝，切分不得扩大访问范围。
- 元数据过滤与向量检索结果取交集；无命中返回空，不强行生成。
