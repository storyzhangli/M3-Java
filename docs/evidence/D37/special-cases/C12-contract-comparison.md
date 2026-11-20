# C12 同步/SSE 引用契约静态对照证据（D37）

- 对照时间：2026-11-20（本地）
- 对照方式：静态源码对照（离线可完成部分）；真实 SSE 端到端帧抓取需应用启动，列入待验证。
- 对照对象：
  - 同步 RAG 出口：`com.zl.d37.rag.dto.RagSourceView`（/api/v1/rag/ask 的 sources）
  - SSE 出口：`com.zl.d37.dto.SsePayloads.Citations(List<CitationDto>)`，由 `ChatStreamController` 在 complete 之前发送（仅 `registry.citedCitations()` 非空时）
  - 前端消费：`static/js/app.js` renderCitations（368-369、434-485 行）+ `isSafeUrl`（394 行起）
  - 传输分帧：`static/js/sse-parser.js`（WHATWG 行结束符归一化、跨块 CRLF 暂存、多行 data LF 拼接）

## 字段契约对照表

| 语义 | RagSourceView（同步） | CitationDto（SSE） | 前端消费 | 一致性结论 |
|---|---|---|---|---|
| 稳定来源标识 | sourceId=documentId | sourceId=documentId | 展示 documentId | 一致 |
| 请求级引用标识 | citationId=sourceRef（S1/S2…） | citationId=id（S1/S2…） | 展示 item.id | 一致（字段名不同、语义相同） |
| 片段稳定标识 | chunkId（UUID v5） | chunkId | 展示 chunkId | 一致 |
| 标题 | title | title | item.title，缺省“未命名文档” | 一致 |
| 引用片段 | quotedSnippet（≤200 字符） | quotedSnippet，回退 excerpt | quotedSnippet \|\| excerpt | 一致（SSE 多一个兼容回退） |
| 是否被实际引用 | cited（全部证据均返回） | cited（SSE 仅发 cited=true 子集） | cited===true 显示“已引用” | 语义一致；SSE 只发被引用子集是设计行为 |
| URL | url 恒 null（NON_NULL 隐藏） | url（NON_NULL 隐藏） | isSafeUrl 通过才建锚点 | 一致；前后端双层防护 |
| 页码 | page/lastPage（无定位隐藏） | page（无 section 级 lastPage） | typeof page==='number' 才显示 | 一致（SSE 无 lastPage，前端也未消费） |
| 段落定位 | paragraphIndex（无定位隐藏） | **无此字段** | typeof paragraphIndex==='number' 分支 | **缺口：SSE 载荷无 paragraphIndex，前端该分支在 SSE 链路恒不触发（死分支）** |
| 版本/切分策略/相似度 | version/strategy/similarity/distance | 无 | 前端未消费 | 同步出口独有评测字段，不构成前端契约破坏 |
| 空值策略 | 类级 @JsonInclude(NON_NULL) | 类级 @JsonInclude(NON_NULL) | 全部字段判空/判类型后渲染 | 一致，无占位假数据 |

## URL 安全策略双层对照

- 后端 `UrlSafetyPolicy`：白名单 http/https；拒危险协议、盘符路径、无主机、localhost/环回/内网；非字面量主机走 DNS 解析复核。
- 前端 `isSafeUrl`：拒空/非字符串、盘符路径（`^[A-Za-z]:[\\/]`）、非 http/https scheme、剥离端口后校验主机（含 IPv6 方括号处理）。
- 结论：前端为第二道防线，与后端白名单方向一致；当前语料 url 全 null，链接渲染面实际关闭。

## 传输层对照

- 后端 `SseEmitter.event().name(eventName).data(payload, APPLICATION_JSON)` 产生标准 `event:`/`data:` 帧；
  sse-parser.js 按 WHATWG 规范解析（三种行结束符、空行分块、data 前导单空格剥离、多行 data LF 拼接、跨块 CRLF 暂存），
  与 Spring MVC SseEmitter 输出格式兼容；node --test 6/6 PASS（见 C11 证据）。

## 发现与处置

1. **契约缺口（低危、展示级）**：CitationDto 无 paragraphIndex，app.js 的段落显示分支在 SSE 链路为死代码。
   不崩溃（typeof 守卫）、不产生假数据。按最小必要原则本迭代不修改公共 DTO 签名，记录为后续迭代候选项。
2. cited 语义两出口一致：同步返回全部证据+cited 标志；SSE 仅推送被引用子集。评测器按同步出口判定，不受影响。
3. 真实 SSE 帧级抓取与同步响应逐字段 diff 需应用启动后执行（依赖 PG_VECTOR_PASSWORD），当前状态：静态对照已完成，动态验证 NOT RUN。
