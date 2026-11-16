# D33 最小 RAG 闭环时序图

> 参与方：Client、RagController、RagOrchestrator、RagRetrievalService、EmbeddingModel、
> VectorStore、ContextAssemblyService、GenerationService、ChatModel。
> 含三条关键分支：检索失败短路、空检索/无证据跳过模型、模型超时受控失败。
> Typora 兼容标准 Mermaid sequenceDiagram 语法。

```mermaid
sequenceDiagram
    participant C as "Client"
    participant CT as "RagController"
    participant O as "RagOrchestrator"
    participant R as "RagRetrievalService"
    participant E as "EmbeddingModel"
    participant V as "VectorStore"
    participant A as "ContextAssemblyService"
    participant G as "GenerationService"
    participant M as "ChatModel"

    C->>CT: "POST /api/v1/rag/ask (question, topK, filters)"
    CT->>O: "ask(request)"
    O->>R: "retrieve(query)"
    R->>E: "embed(question)"
    E-->>R: "query vector (384)"
    R->>V: "similaritySearch(vector, topK, filter)"
    V-->>R: "matched documents"
    R-->>O: "result: SUCCESS / EMPTY / FAILED"

    alt "检索失败 FAILED"
        O-->>CT: "受控失败 RETRIEVAL_FAILED，生成 NOT_ATTEMPTED"
        CT-->>C: "Result(错误码，无 answer)"
    else "检索成功，继续组装"
        O->>A: "assemble(matches)"
        A->>A: "有效性检查 / 去重 / 保留顺序 / 字符预算 / 分配 S1..Sk"
        A-->>O: "context + evidence + budget"

        alt "空检索或无证据"
            O-->>CT: "受控拒答 NO_EVIDENCE，生成 SKIPPED_NO_EVIDENCE"
            Note over O,M: "不调用 GenerationService，ChatModel 调用次数为 0"
            CT-->>C: "Result(拒答文案，sources 为空)"
        else "有证据，进入生成"
            O->>G: "generate(question, context)"
            G->>M: "call(Prompt: 系统规则 + 上下文 + 问题)"

            alt "模型超时"
                M--xG: "读取超时 READ_TIMEOUT"
                G-->>O: "FAILED_TIMEOUT（受控错误，不伪装成功）"
                O-->>CT: "RagAnswerResponse(errorCode=GENERATION_TIMEOUT)"
                CT-->>C: "Result(超时受控错误，answer 为 null)"
            else "模型正常返回"
                M-->>G: "answer（可含 [S1] 引用）"
                G->>G: "核验引用标识是否都在上下文内"
                G-->>O: "GenerationResult.SUCCESS"
                O-->>CT: "RagAnswerResponse(answer + sources)"
                CT-->>C: "Result(answer, sources, budget, timing)"
            end
        end
    end
```
