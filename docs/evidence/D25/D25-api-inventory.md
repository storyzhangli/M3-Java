# D25 API 清单（基于实际 Controller 梳理）

> 全部端点来自现有 5 个控制器，未虚构。统一前缀 `/api/v1`；统一响应包装 `Result<T>`（code/message/data）。
> 通用请求头：`X-Dev-User` 为开发归属标识（正则 [A-Za-z0-9_-]、≤64、缺省 dev-user），**非鉴权凭据**。
> 当前无认证鉴权；端口 8080。

## 1. 端点总览（共 13 个）

| # | 方法 | 路径 | 控制器 | 说明 |
|---|---|---|---|---|
| 1 | POST | /api/v1/ai/chat | ChatController | 同步对话（普通/角色） |
| 2 | GET | /api/v1/ai/health | ChatController | 健康检查 |
| 3 | POST | /api/v1/ai/chat/stream | ChatStreamController | SSE 流式对话 |
| 4 | POST | /api/v1/device/chat | DeviceChatController | 设备场景同步对话 |
| 5 | GET | /api/v1/ai/sessions | HistoryController | 分页会话列表 |
| 6 | GET | /api/v1/ai/sessions/{sessionId}/messages | HistoryController | 分页消息列表 |
| 7 | GET | /api/v1/ai/prompts | PromptTemplateController | 模板列表 |
| 8 | GET | /api/v1/ai/prompts/{templateId} | PromptTemplateController | 模板详情 |
| 9 | POST | /api/v1/ai/prompts | PromptTemplateController | 创建模板 |
| 10 | PUT | /api/v1/ai/prompts/{templateId} | PromptTemplateController | 更新模板（版本自增） |
| 11 | PATCH | /api/v1/ai/prompts/{templateId}/status | PromptTemplateController | 启用/停用 |
| 12 | DELETE | /api/v1/ai/prompts/{templateId} | PromptTemplateController | 逻辑删除 |
| 13 | GET | /api/v1/ai/roles | PromptTemplateController | 可对话角色清单 |

## 2. 对话类接口

### 2.1 POST /api/v1/ai/chat（同步）
- 请求体 ChatRequest：
  - `message` String 必填，硬上限 2000；业务可配上限另校验
  - `scene` String 可选，customer_service/programming
  - `sessionId` String 可选，空则新建
  - `variables` Map 可选，仅允许模板声明变量
  - `thinkingEnabled` Boolean 可选
  - `reasoningEffort` String 可选，LOW/HIGH/MAX
  - `showReasoning` Boolean 可选
  - `retrieveEnabled` Boolean 可选，true 才执行检索
- 响应：Result<Object>；普通链路 ChatReplyResponse，角色链路 RoleChatReplyResponse（含 citations 引用列表）。

### 2.2 POST /api/v1/ai/chat/stream（SSE）
- Content-Type: text/event-stream;charset=UTF-8；请求体同 ChatRequest。
- 命名事件（data 为 JSON）：
  - `start`：requestId/mode/model/sessionId/场景信息
  - `thinking`：推理片段（仅 showReasoning 时）
  - `delta`：正文增量，含 seq
  - `citations`：引用列表
  - `complete`：汇总（mode/model/mocked/总字符数）
  - `error`：code/message
- 响应头：Cache-Control=no-cache、X-Accel-Buffering=no。

### 2.3 POST /api/v1/device/chat（设备场景）
- 请求体 DeviceChatRequest（record）：
  - `scene` String 必填，device_customer_service/technical_engineer
  - `sessionId` String 可选
  - `message` String 必填，上限 4000
  - `variables` Map 可选
- 响应：Result<DeviceChatReply>；本接口不提供正式工单写入。

### 2.4 GET /api/v1/ai/health
- 响应：Result<Map>，含 application、mode、contextWindowMessages、devIdentity。

## 3. 历史查询接口

### 3.1 GET /api/v1/ai/sessions
- 参数：page 默认 1、size 默认 20；按 X-Dev-User 过滤，最近消息时间倒序。
- 响应：Result<PageView<SessionView>>。

### 3.2 GET /api/v1/ai/sessions/{sessionId}/messages
- 参数：page、size；先校验归属，无权返回参数错误。
- 响应：Result<PageView<MessageView>>，消息按序号正序。

## 4. Prompt 模板接口（/api/v1/ai/prompts）

| 方法.路径 | 请求 | 响应/说明 |
|---|---|---|
| GET /prompts | query: scene、status、keyword 可选 | Result<List<PromptTemplateSummary>> |
| GET /prompts/{templateId} | 路径参数 | Result<PromptTemplateView>（含正文/变量） |
| POST /prompts | PromptTemplateUpsertRequest | Result<PromptTemplateView> |
| PUT /prompts/{templateId} | PromptTemplateUpsertRequest | 版本自增 |
| PATCH /prompts/{templateId}/status | TemplateStatusUpdateRequest | 切换 ENABLED/DISABLED |
| DELETE /prompts/{templateId} | — | 逻辑删除，返回 deleted=true |
| GET /roles | — | Result<List<PromptTemplateSummary>>，仅启用角色 |

## 5. 错误与状态口径

- 全局异常经 GlobalExceptionHandler 统一返回 Result；错误码见 ResultCode（AI_CONFIG_ERROR、AI_UNAVAILABLE、SYSTEM_ERROR 等）。
- 流式错误映射：配置/非瞬态→AI_CONFIG_ERROR；瞬态→AI_UNAVAILABLE；超时→错误事件“流式响应超时”。
- 写接口当前无鉴权（风险已标注），后续接入鉴权后在控制器统一加拦截，不在本清单假设已生效。
