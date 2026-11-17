# **费曼口述：Spring AI 的核心 API**

**技术基线：** JDK 21、Spring Boot 3.5.x、Spring AI 1.1.8、PostgreSQL 18.6、pgvector 0.8.x。

这套题的核心不是背诵 160 个 API 名称，而是理解 Spring AI 如何把大模型、向量数据库、Prompt、RAG、记忆和工具调用整合进 Java 工程。

先建立一个总认知：

**Spring AI = 模型抽象 + 统一调用接口 + 数据处理 + 检索增强 + 对话记忆 + 工具调用 + 自动配置 + 可观测性。**

下面按照面试回答方式逐题展开，重点说明 API 的职责、调用关系、使用场景和工程边界。

# **第一阶段：Spring AI 整体架构与核心设计**

## **1. 什么是 Spring AI？它主要解决 Java 应用开发大模型应用时的哪些问题？**

Spring AI 是 Spring 生态中用于开发生成式 AI 应用的框架。

它主要解决以下问题：

1. 不同模型供应商 API 不统一，提供统一的模型抽象。
2. 模型调用代码重复，提供 ChatClient 等统一调用接口。
3. 文档向量化和检索开发复杂，提供 EmbeddingModel 和 VectorStore。
4. RAG 流程需要大量手动编排，提供 Advisor 和检索增强组件。
5. 多轮对话需要管理历史消息，提供 ChatMemory。
6. 大模型需要调用外部业务系统，提供 Tool Calling 和 MCP 集成。
7. Spring 项目组件初始化复杂，提供 Spring Boot Starter 和自动配置。
8. 模型调用难以监控，提供可观测性集成。

例如，Java 应用可以使用 ChatClient 调用 DeepSeek，而不需要在业务 Service 中直接维护 HTTP 请求、鉴权、请求 DTO 和响应解析。

**面试总结：** Spring AI 的核心价值是降低 Java 应用集成大模型的复杂度，将 AI 能力以符合 Spring 编程习惯的方式融入企业应用。

## **2. Spring AI 的整体架构由哪些核心模块组成？**

主要包括：

| 模块                          | 核心职责               |
| ----------------------------- | ---------------------- |
| Model API                     | 统一模型抽象           |
| Chat Client API               | 聊天模型调用入口       |
| Embedding API                 | 文本向量化             |
| Vector Store API              | 向量存储与检索         |
| ETL Pipeline                  | 文档读取、转换和写入   |
| Advisors API                  | 模型调用链增强与编排   |
| Chat Memory                   | 多轮对话记忆           |
| Tool Calling                  | 外部工具调用           |
| MCP                           | 模型与外部工具系统集成 |
| Observability                 | 调用监控和追踪         |
| Spring Boot AutoConfiguration | 自动创建和配置组件     |

这些模块并不是完全独立的。

例如，一个 RAG 请求可以组合：

```
ChatClient
    ↓
Advisor
    ↓
VectorStore
    ↓
EmbeddingModel
    ↓
ChatModel
    ↓
ChatResponse
```

## **3. Spring AI 为什么采用统一 API 抽象不同厂商的大模型？**

不同厂商的模型接口存在差异：

- 请求参数不同。
- 鉴权方式不同。
- 返回数据结构不同。
- 流式输出协议不同。
- Token 使用量统计方式不同。
- 工具调用参数格式不同。

Spring AI 通过统一接口屏蔽常见差异。

例如：

```
public interface ChatModel {

    ChatResponse call(Prompt prompt);

    // 其他模型能力由接口及其相关抽象定义
}
```

业务层依赖 ChatModel 或 ChatClient，而不是直接依赖某个模型供应商的 SDK。

当需要从一个模型供应商切换到另一个供应商时，可以通过更换 Starter 和配置降低业务代码修改量。

但需要注意：统一 API 不代表所有供应商的能力完全一致。部分模型特有参数、工具调用能力和多模态能力仍然需要适配。

## **4. Spring AI 的 Model API、Chat Client API、Vector Store API 和 Advisors API 分别处于什么层次？**

可以从四个层次理解：

| API             | 层次       | 职责                   |
| --------------- | ---------- | ---------------------- |
| Model API       | 模型抽象层 | 定义模型输入输出       |
| ChatClient API  | 应用调用层 | 构建和发起聊天请求     |
| VectorStore API | 数据检索层 | 向量存储和相似度检索   |
| Advisors API    | 调用增强层 | 在调用前后执行增强逻辑 |

举个例子：

用户问：

> 设备 E102 故障应该如何处理？

ChatClient 负责构建聊天请求，Advisor 可以负责 RAG 检索增强，VectorStore 负责搜索相关知识，ChatModel 负责生成答案。

## **5. Spring AI 如何通过 Spring Boot 自动配置完成模型客户端和相关组件的初始化？**

Spring AI 通过 Starter 引入模型供应商的集成依赖，并通过 Spring Boot AutoConfiguration 创建相关 Bean。

例如：

```
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY}
      chat:
        options:
          model: ${AI_MODEL}
          temperature: 0.2
```

应用启动时，大致经过：

```
Spring Boot 启动
      ↓
加载 AutoConfiguration
      ↓
读取 application.yml
      ↓
创建模型相关 Bean
      ↓
创建 ChatModel
      ↓
创建 ChatClient.Builder
      ↓
业务 Service 注入使用
```

实际自动配置条件取决于对应 Starter、依赖、配置属性以及是否已经存在自定义 Bean。

## **6. Spring AI 的 Starter、AutoConfiguration 和核心 API 之间是什么关系？**

三者分工不同：

- Starter：负责引入依赖。
- AutoConfiguration：负责自动创建和配置 Bean。
- 核心 API：负责提供统一的编程接口。

例如：

```
spring-ai-starter-model-openai
             ↓
       自动配置类
             ↓
          ChatModel
             ↓
          ChatClient
             ↓
        业务 Service
```

Starter 不等于 API，AutoConfiguration 也不等于模型本身。

## **7. Spring AI 的 API 抽象与具体模型厂商 SDK 有什么区别？**

Spring AI API 是统一的 Java 抽象，厂商 SDK 是对具体模型服务能力的直接封装。

| 对比        | Spring AI          | 厂商 SDK       |
| ----------- | ------------------ | -------------- |
| API         | 统一抽象           | 厂商专有       |
| 供应商切换  | 相对容易           | 通常需要修改   |
| Spring 集成 | 原生适配           | 需要自行整合   |
| 特有功能    | 取决于适配程度     | 通常支持更直接 |
| 底层控制    | 抽象层可能隐藏细节 | 更直接         |

企业项目可以优先使用 Spring AI，遇到抽象无法满足的特殊需求时，再考虑使用厂商 SDK。

## **8. Spring AI 如何在统一接口的基础上保留不同模型厂商的特有能力？**

主要通过模型专有 Options、扩展配置和供应商适配实现。

例如：

```
OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model("具体模型名称")
        .temperature(0.2)
        .build();
```

然后：

```
ChatResponse response = chatModel.call(
        new Prompt("分析设备故障", options)
);
```

不同模型供应商可以提供自己的 Options 类型。

因此，统一 API 负责通用能力，供应商扩展负责特有能力。

## **9. Spring AI 中哪些组件负责模型调用，哪些组件负责业务编排？**

**模型调用组件：**

- ChatModel
- EmbeddingModel
- ImageModel 等其他模型抽象

**业务调用和编排组件：**

- ChatClient
- Advisors
- VectorStore
- ChatMemory
- ToolCallback
- ETL Pipeline

需要注意，ChatClient 是模型调用的高级客户端，不等于完整业务编排框架。复杂 RAG 业务通常还需要自己的 Service 和领域组件。

## **10. Spring AI 的核心 API 如何协同完成一次 RAG 问答？**

一次典型调用：

```
用户问题
   ↓
ChatClient
   ↓
QuestionAnswerAdvisor
   ↓
VectorStore
   ↓
EmbeddingModel
   ↓
检索相关 Document
   ↓
上下文注入 Prompt
   ↓
ChatModel
   ↓
ChatResponse
   ↓
返回答案
```

如果是企业级 RAG，可以把检索、权限、版本控制、上下文组装和引用校验独立出来，避免把全部逻辑放在一个 Advisor 中。

# **第二阶段：ChatModel API 与模型调用**

## **11. 什么是 ChatModel？它在 Spring AI 中承担什么职责？**

ChatModel 是 Spring AI 的聊天模型核心抽象。

主要负责：

- 接收 Prompt。
- 调用具体模型供应商。
- 将请求转换为供应商格式。
- 将供应商响应转换为统一的 ChatResponse。
- 支持模型同步调用和流式调用。

它属于模型调用层。

## **12. ChatModel 与具体模型厂商的 API 有什么关系？**

ChatModel 是统一抽象，具体供应商实现负责对接厂商 API。

例如：

```
ChatModel
   ├── OpenAI 适配实现
   ├── Anthropic 适配实现
   └── 其他供应商适配实现
```

业务代码依赖抽象，底层适配实现负责处理不同厂商的通信协议。

## **13. ChatModel 的 call 方法执行什么操作？**

`call(Prompt prompt)` 接收 Prompt，调用底层模型并返回 ChatResponse。

例如：

```
@Autowired
private ChatModel chatModel;

public String ask(String question) {

    Prompt prompt = new Prompt(
            new UserMessage(question)
    );

    ChatResponse response = chatModel.call(prompt);

    return response.getResult()
            .getOutput()
            .getText();
}
```

执行过程：

```
Prompt
  ↓
ChatModel.call()
  ↓
供应商模型 API
  ↓
模型生成
  ↓
ChatResponse
  ↓
提取文本
```

## **14. ChatModel 与 ChatClient 有什么区别？**

这是 Spring AI 面试中的高频题。

| ChatModel             | ChatClient                      |
| --------------------- | ------------------------------- |
| 底层模型抽象          | 高级调用客户端                  |
| 直接接收 Prompt       | 提供 Fluent API                 |
| 直接返回 ChatResponse | 支持 content、entity 等便捷操作 |
| 适合底层模型调用      | 适合业务开发                    |

简单理解：

ChatModel 负责调用模型，ChatClient 负责让开发者更方便地构建和使用模型调用。

## **15. 为什么业务代码通常更适合使用 ChatClient，而不是直接调用 ChatModel？**

因为 ChatClient 提供更友好的调用方式：

```
String answer = chatClient.prompt()
        .user("什么是 RAG？")
        .call()
        .content();
```

相比直接创建 Prompt 和解析 ChatResponse，代码更简洁。

同时支持：

- System Message。
- User Message。
- Prompt Template。
- 模型 Options。
- Advisors。
- 结构化输出。
- 流式调用。

## **16. ChatModel 的输入 Prompt 包含哪些内容？**

Prompt 主要包含一组 Message 和可选的模型调用参数。

例如：

```
Prompt
  ├── SystemMessage
  ├── UserMessage
  ├── AssistantMessage
  └── ChatOptions
```

其中 ChatOptions 可以控制模型名称、Temperature、Max Tokens 等参数，具体支持范围取决于供应商。

## **17. ChatModel 的输出 ChatResponse 包含哪些信息？**

ChatResponse 通常包含：

- Generation：模型生成结果。
- ChatResponseMetadata：响应元数据。
- Token 使用统计信息（供应商提供时）。

例如：

```
ChatResponse response = chatModel.call(prompt);

String content = response.getResult()
        .getOutput()
        .getText();

Usage usage = response.getMetadata().getUsage();
```

Token Usage 可能无法在所有供应商或所有响应场景下完整获取，不能假设一定存在。

## **18. Prompt、Message、ChatResponse 和 Generation 之间是什么关系？**

| 对象         | 作用             |
| ------------ | ---------------- |
| Prompt       | 模型请求整体封装 |
| Message      | 请求中的单条消息 |
| ChatResponse | 模型响应整体封装 |
| Generation   | 一条模型生成结果 |

关系：

```
Prompt
  └── Message 列表

ChatResponse
  ├── Metadata
  └── Generation
        └── AssistantMessage
```

## **19. SystemMessage、UserMessage 和 AssistantMessage 分别承担什么职责？**

- SystemMessage：定义模型行为规则和角色。
- UserMessage：表达用户输入。
- AssistantMessage：表示模型生成的历史消息或响应消息。

例如：

```
List<Message> messages = List.of(
        new SystemMessage("你是设备维修助手"),
        new UserMessage("设备 E102 报警怎么办？")
);

Prompt prompt = new Prompt(messages);
```

## **20. ChatOptions 有什么作用？如何设置模型名称、Temperature 和 Max Tokens？**

ChatOptions 用于控制模型生成参数。

例如：

```
ChatOptions options = OpenAiChatOptions.builder()
        .model("具体模型名称")
        .temperature(0.2)
        .maxTokens(1000)
        .build();
```

然后将 Options 传给 Prompt。

不同供应商对参数名称、支持范围和含义可能存在差异。

## **21. ChatOptions 与具体模型厂商的专有参数有什么关系？**

ChatOptions 提供通用模型配置抽象，具体供应商 Options 提供额外参数。

例如：

```
OpenAiChatOptions
```

是 OpenAI 模型适配相关的配置类型。

当使用供应商专有参数时，业务代码可能与具体供应商产生耦合。

因此，通用业务逻辑尽量依赖通用 Options，特殊能力通过独立适配层处理。

## **22. 如何使用 ChatModel 实现一次最基础的同步模型调用？**

```
@Service
public class ModelService {

    private final ChatModel chatModel;

    public ModelService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String ask(String question) {

        Prompt prompt = new Prompt(
                new UserMessage(question)
        );

        ChatResponse response = chatModel.call(prompt);

        return response.getResult()
                .getOutput()
                .getText();
    }
}
```

这个例子展示了 ChatModel 最基本的调用方式。

## **23. ChatModel 如何实现流式输出？同步调用与流式调用有什么区别？**

ChatModel 提供 `stream(Prompt)`，返回 `Flux<ChatResponse>`。

同步调用：

```
ChatResponse response = chatModel.call(prompt);
```

流式调用：

```
Flux<ChatResponse> stream = chatModel.stream(prompt);
```

区别：

| 同步调用     | 流式调用             |
| ------------ | -------------------- |
| 等待完整响应 | 持续接收响应片段     |
| 适合普通问答 | 适合长文本生成       |
| 简单处理     | 需要处理响应生命周期 |
| 一次返回     | 多次发出数据         |

注意，流式接口不意味着模型一定逐字返回，实际粒度由模型供应商和适配实现决定。

## **24. 如何处理模型调用超时、限流、异常和响应失败？**

需要在模型调用外围建立可靠性治理。

主要措施：

1. 设置连接超时和响应超时。
2. 根据错误类型实施有限重试。
3. 对 HTTP 429 实施退避和限流。
4. 使用熔断器保护外部服务。
5. 使用独立线程池或并发隔离。
6. 记录 TraceId 和错误分类。
7. 返回统一业务异常。

注意，不应对所有异常无条件重试。请求超时也不代表模型端没有执行成功。

## **25. 如何通过统一 ChatModel API 切换不同模型供应商？**

通过更换模型 Starter、配置和底层 ChatModel Bean 实现。

例如：

```
业务 Service
     ↓
 ChatClient
     ↓
 ChatModel
     ↓
供应商 A / 供应商 B
```

如果业务层没有使用供应商专有参数，切换成本通常较低。

但不同供应商的结构化输出、Tool Calling 和流式协议可能存在差异，需要进行兼容性验证。

# **第三阶段：ChatClient API 与流式调用**

## **26. 什么是 ChatClient？它解决了 ChatModel 直接调用中的哪些开发问题？**

ChatClient 是 Spring AI 提供的高级聊天客户端 API。

它提供 Fluent API，简化：

- Prompt 构建。
- Message 组织。
- 模型参数配置。
- Advisor 集成。
- 结构化输出。
- 同步与流式调用。

例如：

```
String answer = chatClient.prompt()
        .user("解释 Spring AI")
        .call()
        .content();
```

## **27. ChatClient 与 Spring WebClient、RestClient 的 API 设计风格有什么相似之处？**

三者都采用链式调用风格。

例如：

```
webClient.get()
        .uri("/users")
        .retrieve();
```

ChatClient：

```
chatClient.prompt()
        .user("你好")
        .call()
        .content();
```

共同点是通过 Fluent API 将请求构建、执行和结果处理串联起来。

区别是 WebClient 和 RestClient 是 HTTP 客户端，而 ChatClient 是面向聊天模型的应用抽象。

## **28. ChatClient.Builder 的作用是什么？**

用于构建 ChatClient。

例如：

```
@Bean
public ChatClient chatClient(ChatClient.Builder builder) {

    return builder
            .defaultSystem("你是企业设备维修助手")
            .build();
}
```

可以配置默认 System Message、默认 Advisor 和其他客户端级设置。

## **29. ChatClient.create(chatModel) 与 ChatClient.builder(chatModel).build() 有什么区别？**

`ChatClient.create(chatModel)` 是便捷的创建方式。

`ChatClient.builder(chatModel).build()` 可以在构建过程中进一步配置客户端。

例如：

```
ChatClient client = ChatClient.builder(chatModel)
        .defaultSystem("你是维修助手")
        .build();
```

需要注意，两者都基于指定的 ChatModel，并不意味着前者不能使用模型本身已有的配置。

## **30. ChatClient 的 prompt()、call() 和 stream() 分别承担什么职责？**

- `prompt()`：开始构建一次聊天请求。
- `call()`：执行同步调用。
- `stream()`：执行流式调用。

例如：

```
String answer = chatClient.prompt()
        .user("什么是 Embedding？")
        .call()
        .content();
```

流式：

```
Flux<String> answer = chatClient.prompt()
        .user("解释 RAG 完整链路")
        .stream()
        .content();
```

## **31. ChatClient 如何构建 System Message 和 User Message？**

通过 `.system()` 和 `.user()`：

```
String answer = chatClient.prompt()
        .system("你是 Java AI 工程师")
        .user("解释 VectorStore")
        .call()
        .content();
```

也可以使用 Consumer 或 PromptTemplate 等方式构建动态消息。

## **32. ChatClient 如何使用 .user() 和 .system() 设置提示词？**

简单场景：

```
chatClient.prompt()
        .system("回答必须准确")
        .user("什么是 RAG？")
        .call()
        .content();
```

复杂场景可以分别维护 System Prompt 和 User Prompt 模板，避免把全部提示词写在业务方法中。

## **33. ChatClient 如何通过 Prompt Template 实现动态参数替换？**

PromptTemplate 可以定义带变量的提示词。

例如：

```
你是设备维修助手。

设备型号：{deviceModel}
故障代码：{faultCode}

请分析故障。
```

运行时替换变量：

```
String answer = chatClient.prompt()
        .user(userSpec -> userSpec
                .text("""
                    设备型号：{deviceModel}
                    故障代码：{faultCode}
                    """)
                .param("deviceModel", "XYZ-200")
                .param("faultCode", "E102"))
        .call()
        .content();
```

## **34. 如何使用 .param() 向 Prompt Template 传递变量？**

`.param()` 用于给消息模板提供变量值。

例如：

```
chatClient.prompt()
        .user(userSpec -> userSpec
                .text("请分析设备 {model} 的 {fault} 故障")
                .param("model", "XYZ-200")
                .param("fault", "E102"))
        .call()
        .content();
```

模板渲染后，变量被替换为实际内容。

## **35. ChatClient 如何通过 .options() 配置模型调用参数？**

```
String answer = chatClient.prompt()
        .user("解释 RAG")
        .options(OpenAiChatOptions.builder()
                .temperature(0.2)
                .maxTokens(1000)
                .build())
        .call()
        .content();
```

`.options()` 设置当前请求的模型参数。

一般而言，请求级 Options 用于覆盖或补充默认参数，具体合并规则需要以实际实现为准。

## **36. ChatClient 如何通过 .entity() 将模型输出转换为 Java POJO？**

先定义 DTO：

```
public record FaultAnalysis(
        String faultCode,
        String summary,
        List<String> suggestions
) {}
```

然后：

```
FaultAnalysis result = chatClient.prompt()
        .user("分析设备 E102 故障，并输出结构化结果")
        .call()
        .entity(FaultAnalysis.class);
```

Spring AI 会通过结构化输出转换机制，将模型返回内容映射为 Java 对象。

但必须注意：转换成功不代表业务数据真实可靠。

## **37. entity() 与 content() 有什么区别？**

| content()            | entity()       |
| -------------------- | -------------- |
| 返回文本             | 返回结构化对象 |
| 适合自然语言问答     | 适合业务 DTO   |
| 需要自行解析复杂格式 | 框架协助转换   |

例如：

```
String answer = responseSpec.content();

FaultAnalysis analysis =
        responseSpec.entity(FaultAnalysis.class);
```

## **38. ChatClient 如何获取 ChatResponse、Metadata 和 Token Usage？**

使用 `.chatResponse()` 获取完整 ChatResponse。

```
ChatResponse response = chatClient.prompt()
        .user("分析设备故障")
        .call()
        .chatResponse();
```

再读取：

```
String answer = response.getResult()
        .getOutput()
        .getText();

ChatResponseMetadata metadata = response.getMetadata();

Usage usage = metadata.getUsage();
```

与 `.content()` 相比，`.chatResponse()` 更适合需要记录模型元数据、Token 使用量和响应信息的场景。

## **39. ChatClient 如何通过 .stream() 实现响应式流式输出？**

```
Flux<String> flux = chatClient.prompt()
        .user("详细解释 RAG")
        .stream()
        .content();
```

Spring WebFlux Controller 可以返回：

```
@GetMapping(
    value = "/chat/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE
)
public Flux<String> stream(String question) {

    return chatClient.prompt()
            .user(question)
            .stream()
            .content();
}
```

生产环境还需要考虑异常信号、连接取消、超时、背压和资源释放。

## **40. ChatClient 的 defaultSystem、defaultUser 和 defaultAdvisors 有什么作用？**

它们用于设置 ChatClient 的默认请求行为。

- `defaultSystem()`：默认系统提示词。
- `defaultUser()`：默认用户消息或默认用户消息构建规则。
- `defaultAdvisors()`：默认 Advisor 链。

例如：

```
ChatClient client = builder
        .defaultSystem("你是企业设备维修助手")
        .defaultAdvisors(
                new SimpleLoggerAdvisor()
        )
        .build();
```

实际项目中需要注意默认消息和请求级消息的组合关系，避免重复注入 Prompt。

## **41. ChatClient 的 mutate() 方法有什么用途？**

用于基于现有 ChatClient 创建一个可修改的 Builder。

例如：

```
ChatClient baseClient = builder.build();

ChatClient customerClient = baseClient.mutate()
        .defaultSystem("你是客户服务助手")
        .build();
```

适合从公共配置派生不同业务场景的 ChatClient。

## **42. 如何设计可复用的 ChatClient Bean，避免每次请求重复构建客户端？**

通过 Spring Bean 管理：

```
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder) {

        return builder
                .defaultSystem("你是企业 AI 助手")
                .build();
    }
}
```

业务层通过构造器注入：

```
@Service
public class RagService {

    private final ChatClient chatClient;

    public RagService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }
}
```

这样可以复用客户端配置，避免在每次 HTTP 请求中重复构建。

## **43. ChatClient 的同步调用与流式调用分别适合什么业务场景？**

同步调用适合：

- 简单问答。
- 结构化数据提取。
- 工单分类。
- 后台批量任务。

流式调用适合：

- 长文本回答。
- AI 对话界面。
- 实时生成报告。
- 需要逐步展示结果的场景。

选择依据是业务交互需求，而不是认为流式一定比同步性能更高。

# **第四阶段：Prompt、Message 与结构化输出 API**

## **44. 什么是 Prompt？它与普通字符串有什么区别？**

Prompt 是模型调用请求的结构化封装。

普通字符串只能表示一段文本，而 Prompt 可以包含：

- 多条 Message。
- 不同角色。
- 模型调用参数。

因此，Prompt 能表达更完整的模型请求上下文。

## **45. Prompt 在 Spring AI 中由哪些核心对象组成？**

主要包括：

```
Prompt
  ├── List<Message>
  │      ├── SystemMessage
  │      ├── UserMessage
  │      └── AssistantMessage
  │
  └── ChatOptions
```

Prompt 负责把消息和模型调用选项组合成一次请求。

## **46. Message 接口及其不同实现分别代表什么消息类型？**

Message 是消息抽象。

常见类型：

- SystemMessage：系统指令。
- UserMessage：用户输入。
- AssistantMessage：模型回答。
- ToolResponseMessage：工具调用后的结果消息。

不同消息类型具有不同的语义，模型适配器负责将它们转换为供应商需要的格式。

## **47. PromptTemplate 的作用是什么？**

PromptTemplate 用于将固定提示词模板与动态变量组合。

例如：

```
PromptTemplate template = new PromptTemplate(
        "请分析设备 {model} 的故障 {faultCode}"
);

Prompt prompt = template.create(Map.of(
        "model", "XYZ-200",
        "faultCode", "E102"
));
```

适合把 Prompt 模板与 Java 业务逻辑分离。

## **48. Spring AI 默认使用什么模板渲染机制？**

Spring AI 1.1.x 的 PromptTemplate 默认采用 StringTemplate（ST）模板引擎进行变量渲染。

使用时需要注意模板变量与普通文本中的特殊字符可能产生冲突。

## **49. 如何避免 Prompt Template 中变量替换与普通文本占位符产生冲突？**

可以：

1. 统一模板变量命名。
2. 避免无意中使用模板语法。
3. 对包含 JSON 示例的 Prompt 特别检查花括号。
4. 必要时使用转义或调整模板写法。
5. 为模板建立单元测试。

尤其是 JSON 示例：

```
{
  "device": "{deviceModel}"
}
```

需要区分真正的模板变量和普通 JSON 花括号。

## **50. 如何通过 Resource 加载外部 Prompt 模板？**

可以通过 Spring Resource 读取 classpath 中的 Prompt 文件。

例如：

```
resources/
  prompts/
    fault-analysis.st
```

Java 代码可以通过 `ResourceLoader` 加载资源，再将模板内容交给 PromptTemplate。

这样可以实现：

- Prompt 外置。
- 不同场景使用不同模板。
- 版本化管理。
- 独立测试。

企业项目还可以增加模板 ID、版本号、场景和更新时间。

## **51. Structured Output 是什么？为什么需要将模型输出映射为 Java 对象？**

Structured Output 是让模型按照预期结构生成内容，并转换成指定数据类型的能力。

例如：

```
{
  "faultCode": "E102",
  "faultType": "传感器异常",
  "severity": "HIGH"
}
```

相比自由文本，结构化输出更容易：

- 持久化。
- 参数校验。
- 业务处理。
- 工单集成。
- 自动化测试。

## **52. BeanOutputConverter 的作用是什么？**

BeanOutputConverter 用于将模型输出转换为 Java Bean。

它可以根据目标类型生成结构化输出相关的格式指导，并将模型生成的内容解析为 Java 对象。

例如：

```
BeanOutputConverter<FaultAnalysis> converter =
        new BeanOutputConverter<>(FaultAnalysis.class);

String format = converter.getFormat();

FaultAnalysis result = converter.convert(json);
```

具体转换行为还需要考虑目标类型、JSON 格式和字段定义。

## **53. 如何将模型返回的 JSON 内容转换为 Java DTO？**

例如：

```
public record FaultDTO(
        String faultCode,
        String summary
) {}
```

通过 BeanOutputConverter：

```
BeanOutputConverter<FaultDTO> converter =
        new BeanOutputConverter<>(FaultDTO.class);

FaultDTO dto = converter.convert(json);
```

也可以通过 ChatClient 的 `.entity(FaultDTO.class)` 完成调用与转换。

## **54. 如何处理模型输出格式错误、字段缺失和类型不匹配？**

分为三层：

1. 格式层：JSON 是否合法。
2. 类型层：字段类型是否匹配。
3. 业务层：字段值是否符合业务规则。

可以使用：

- JSON 解析异常处理。
- Bean Validation。
- 自定义业务校验器。
- 有限次数格式修复。
- 人工审核。

不能把模型返回的 JSON 直接当作可信业务数据。

## **55. 结构化输出转换成功是否意味着模型生成的数据一定符合业务事实？**

不意味着。

例如模型返回：

```
{
  "temperature": 500
}
```

即使成功转换成 Java 对象，也不能证明设备温度确实为 500℃。

结构化输出只保证数据结构层面的可解析性，不保证事实正确性。

## **56. 如何结合 Bean Validation 对模型生成的结构化数据进行校验？**

DTO：

```
public record FaultDTO(

        @NotBlank
        String faultCode,

        @NotBlank
        String summary
) {}
```

调用：

```
Set<ConstraintViolation<FaultDTO>> violations =
        validator.validate(dto);

if (!violations.isEmpty()) {
    throw new IllegalArgumentException(
            "模型输出未通过业务校验"
    );
}
```

对于维修操作，还应进一步校验设备状态、权限和业务规则。

# **第五阶段：EmbeddingModel API 与向量化**

## **57. 什么是 EmbeddingModel？它在 RAG 中承担什么职责？**

EmbeddingModel 是 Spring AI 的文本嵌入模型抽象。

它负责把文本转换成向量。

例如：

```
"设备传感器异常"
        ↓
EmbeddingModel
        ↓
[0.12, -0.23, 0.56, ...]
```

向量用于表达文本的语义特征，供后续相似度检索使用。

## **58. EmbeddingModel 与 ChatModel 有什么区别？**

| EmbeddingModel         | ChatModel            |
| ---------------------- | -------------------- |
| 文本向量化             | 自然语言生成         |
| 输出向量               | 输出消息             |
| 用于检索               | 用于回答             |
| 通常不生成自然语言答案 | 生成文本或结构化内容 |

RAG 同时使用二者：

EmbeddingModel 找知识，ChatModel 根据知识生成答案。

## **59. EmbeddingModel 的 embed() 方法执行什么操作？**

`embed(String text)` 将输入文本转换为一个向量。

例如：

```
float[] vector = embeddingModel.embed(
        "设备 E102 传感器异常"
);
```

此外，EmbeddingModel 还提供批量处理相关方法，具体方法签名以 1.1.8 Javadoc 为准。

## **60. EmbeddingRequest 和 EmbeddingResponse 分别承担什么职责？**

- EmbeddingRequest：封装向量化请求。
- EmbeddingResponse：封装向量化响应。

响应通常包含：

- Embedding 结果。
- 模型响应元数据。
- 使用量信息（如果供应商提供）。

## **61. Embedding 与 EmbeddingModel 的关系是什么？**

Embedding 是一次文本向量化的结果对象。

EmbeddingModel 是执行向量化任务的模型抽象。

可以理解为：

```
EmbeddingModel
      ↓
输入文本
      ↓
Embedding
      ↓
向量数据
```

## **62. EmbeddingModel 如何将一段文本转换成固定维度的向量？**

通过预训练的嵌入模型，将文本映射到一个固定维度的向量空间。

例如：

```
文本 A → [0.1, 0.2, 0.3, ...]
文本 B → [0.2, 0.3, 0.4, ...]
```

语义相近的文本通常在向量空间中更接近。

向量维度由模型决定，并不是由文本长度决定。

## **63. 为什么相同模型对相同文本通常能够生成可比较的向量表示？**

因为同一个模型使用相同的参数和向量空间规则处理输入文本。

因此，相同模型、相同配置和相同预处理条件下产生的向量具有可比较性。

但模型版本、Tokenizer、预处理、任务类型等变化，都可能影响向量结果。

## **64. EmbeddingModel 的维度、模型版本和向量空间之间有什么关系？**

三者关系密切：

- 维度决定向量长度。
- 模型版本决定向量生成规则。
- 向量空间决定向量的语义分布。

例如，入库使用 1024 维模型，查询使用 1536 维模型，二者通常无法直接进行向量比较。

即使维度相同，也不能保证不同模型生成的向量处于兼容空间。

## **65. 如何通过 EmbeddingModel 实现文档 Chunk 的向量化？**

```
String chunk = "设备 E102 表示传感器信号异常";

float[] vector = embeddingModel.embed(chunk);
```

如果有多个 Chunk，可以批量向量化，减少外部请求开销。

生产环境通常优先通过 VectorStore 的 `add()` 完成向量化和写入，由 VectorStore 使用配置好的 EmbeddingModel。

## **66. 如何通过 EmbeddingModel 实现用户 Query 的向量化？**

```
String query = "设备 E102 报警如何处理？";

float[] queryVector = embeddingModel.embed(query);
```

之后将 Query Vector 用于向量相似度检索。

在 Spring AI 1.1.8 的标准 VectorStore 抽象中，通常直接传入文本 Query，由 VectorStore 内部完成向量化。

## **67. 为什么文档入库和在线查询必须使用兼容的 Embedding 模型？**

因为向量检索依赖向量之间的空间关系。

如果文档向量和 Query 向量来自不兼容的向量空间，那么即使维度相同，距离计算也可能没有语义意义。

因此需要保证：

- 模型兼容。
- 向量维度一致。
- 预处理策略一致。
- 向量空间一致。

## **68. 如何通过批量 Embedding 降低模型调用开销？**

将多个 Chunk 合并为一次批量请求。

例如：

```
Chunk 1
Chunk 2
Chunk 3
Chunk 4
      ↓
批量 Embedding
      ↓
多个向量结果
```

优势：

- 减少 HTTP 请求次数。
- 提高吞吐量。
- 降低请求开销。

需要注意模型的批量大小限制、Token 限制和限流策略。

## **69. 如何处理 Embedding 调用失败、超时和向量维度不匹配？**

分情况处理：

- 网络失败：有限重试。
- 服务限流：退避重试。
- 超时：记录任务状态，支持恢复。
- 维度不匹配：阻止写入。
- 模型版本变化：创建新的向量空间或索引策略。

不能把不同向量模型的结果直接混合写入同一个不兼容的向量列。

## **70. EmbeddingModel 的 Token 使用量和模型调用成本如何统计？**

主要记录：

- 请求次数。
- 输入 Token 数量。
- 模型名称。
- 请求耗时。
- 成功和失败数量。
- 模型计费信息。

如果供应商返回 Usage，可以读取真实统计值。

如果供应商没有返回 Usage，可以通过 Tokenizer 估算，但需要明确标记为估算值。

# **第六阶段：VectorStore API 与向量数据库**

## **71. 什么是 VectorStore？它在 Spring AI 中承担什么职责？**

VectorStore 是 Spring AI 的向量存储抽象。

主要负责：

- 添加 Document。
- 删除 Document。
- 相似度检索。
- Metadata 过滤。

它让业务层不必直接依赖某一种向量数据库的底层操作。

## **72. VectorStore 与 PostgreSQL、pgvector 之间是什么关系？**

三者关系：

```
Java 业务代码
      ↓
Spring AI VectorStore
      ↓
PostgreSQLVectorStore
      ↓
JDBC / SQL
      ↓
PostgreSQL
      ↓
pgvector
```

VectorStore 是统一抽象。

PostgreSQLVectorStore 是具体实现。

pgvector 是 PostgreSQL 的向量扩展，提供向量数据类型、距离运算和索引能力。

## **73. VectorStore 的 add()、delete() 和 similaritySearch() 分别执行什么操作？**

| 方法               | 作用                        |
| ------------------ | --------------------------- |
| add()              | 写入 Document 和向量        |
| delete()           | 删除指定文档                |
| similaritySearch() | 根据文本 Query 检索相似文档 |

例如：

```
vectorStore.add(documents);

List<Document> results =
        vectorStore.similaritySearch("设备 E102 故障");
```

## **74. VectorStore 为什么能够屏蔽不同向量数据库之间的底层差异？**

因为它统一了常用的数据操作接口。

例如：

```
VectorStore vectorStore;
```

业务代码可以面向接口编程，而不需要直接编写某个数据库的专有查询语句。

但是，不同数据库的索引、过滤表达式、距离算法和性能特点仍然可能不同。

## **75. Spring AI 的 Document 对象包含哪些核心信息？**

主要包括：

- Document ID。
- Text Content。
- Metadata。

例如：

```
Document document = new Document(
        "设备 E102 表示传感器异常",
        Map.of(
                "deviceModel", "XYZ-200",
                "faultCode", "E102",
                "version", "v2"
        )
);
```

Document 是知识片段及其业务元数据的载体。

## **76. Document 的文本内容与 Metadata 分别承担什么职责？**

Content 保存实际知识内容。

Metadata 保存辅助检索、过滤和追溯的信息。

例如：

```
Content:
设备 E102 表示传感器信号异常。

Metadata:
deviceModel = XYZ-200
faultCode = E102
version = v2
page = 12
```

Content 主要用于语义检索和模型上下文，Metadata 主要用于过滤和业务管理。

## **77. 如何通过 VectorStore 将 Document 写入 PostgreSQL/pgvector？**

先配置 PostgreSQLVectorStore，然后：

```
vectorStore.add(documents);
```

Spring AI 会根据实现完成：

1. 获取 Document 内容。
2. 调用 EmbeddingModel 生成向量。
3. 保存 Document 内容。
4. 保存 Metadata。
5. 保存向量。

实际数据库表结构由 VectorStore Schema 配置决定。

## **78. VectorStore 如何在内部使用 EmbeddingModel 生成文档向量？**

在标准实现中，VectorStore 持有或使用 EmbeddingModel。

当调用：

```
vectorStore.add(documents);
```

内部会将 Document 内容提交给 EmbeddingModel，得到向量，再将文本、Metadata 和向量写入数据库。

这也是为什么向量入库阶段通常不需要业务代码手动调用 `embed()`。

## **79. similaritySearch() 如何完成相似度检索？**

标准流程：

```
用户 Query
    ↓
EmbeddingModel
    ↓
Query Vector
    ↓
PostgreSQLVectorStore
    ↓
pgvector 距离计算
    ↓
排序
    ↓
返回 Top-K Document
```

## **80. SearchRequest 是什么？它包含哪些核心配置？**

SearchRequest 用于封装检索请求参数。

主要配置包括：

- Query。
- Top-K。
- Similarity Threshold。
- Filter Expression。

它让检索条件不再分散在业务代码中。

## **81. SearchRequest 如何设置 Top-K 和 similarityThreshold？**

例如：

```
SearchRequest request = SearchRequest.builder()
        .query("设备 E102 报警")
        .topK(5)
        .similarityThreshold(0.75)
        .build();

List<Document> results =
        vectorStore.similaritySearch(request);
```

Top-K 控制候选结果数量。

Similarity Threshold 控制相关性筛选。

注意，具体分数语义和阈值边界需要结合当前 VectorStore 实现确认。

## **82. 如何通过 Metadata Filter 实现设备型号、租户和文档类型过滤？**

例如：

```
SearchRequest request = SearchRequest.builder()
        .query("设备传感器故障")
        .topK(5)
        .filterExpression(
                "deviceModel == 'XYZ-200' && tenantId == 'T001'"
        )
        .build();
```

通过 Metadata Filter 限制候选知识范围。

对于企业系统，租户过滤必须由可信服务端上下文生成，不能直接相信客户端传来的 tenantId。

## **83. 为什么 Metadata Filter 不能代替完整的业务权限系统？**

Metadata Filter 只是检索过滤机制。

完整权限控制还需要：

- 身份认证。
- 用户授权。
- 租户隔离。
- 资源权限校验。
- 数据访问审计。
- 最终上下文权限复核。

否则，攻击者可能通过修改请求参数访问其他租户的数据。

## **84. VectorStore 返回的 Document.getScore() 应该如何理解？**

它表示当前检索实现返回的相关性分数。

但不能直接理解为：

- 模型回答置信度。
- 答案正确率。
- 用户问题得到正确回答的概率。

尤其需要注意，不同 VectorStore 对分数方向和转换方式可能不同。

必须查看具体实现的分数定义。

## **85. Spring AI VectorStore 与直接编写 pgvector SQL 查询有什么区别？**

| VectorStore        | 直接 SQL           |
| ------------------ | ------------------ |
| 统一 API           | 数据库专有实现     |
| 开发简单           | 控制能力强         |
| 易于更换数据库     | 深度利用数据库能力 |
| 复杂查询受抽象限制 | 可以精细优化       |

企业项目可以优先用 VectorStore 实现通用检索，对复杂过滤、索引优化和特殊 SQL 场景使用专门的 Repository。

## **86. 如何通过 VectorStore 实现文档更新、删除和版本管理？**

一般使用：

1. Document ID。
2. Source Document ID。
3. Version。
4. Document Status。
5. Chunk ID。

更新时不能简单地把新 Chunk 加进去就结束。

应该保证新版本完整入库并通过校验后，再切换当前有效版本，旧版本退出在线检索。

## **87. 如何保证向量入库与业务数据库中的文档状态保持一致？**

采用文档状态机和可靠任务机制。

例如：

```
UPLOADED
    ↓
PARSING
    ↓
CHUNKING
    ↓
EMBEDDING
    ↓
INDEXING
    ↓
ACTIVE
```

失败则进入 FAILED 或 RETRYING。

可以结合：

- 数据库事务。
- Outbox。
- 幂等任务。
- 重试机制。
- 版本切换。
- 定期对账。

不能假设业务数据库事务和向量数据库写入天然属于同一个原子事务。

# **第七阶段：Document 与 ETL Pipeline API**

## **88. 什么是 Spring AI ETL Pipeline？它解决了什么问题？**

ETL Pipeline 是 Spring AI 提供的文档处理流程抽象。

ETL 分别代表：

- Extract：提取。
- Transform：转换。
- Load：加载。

它把文档读取、清洗、切块、向量化和写入等步骤组织起来。

## **89. DocumentReader、DocumentTransformer 和 DocumentWriter 分别承担什么职责？**

| 组件                | 职责             |
| ------------------- | ---------------- |
| DocumentReader      | 读取原始数据     |
| DocumentTransformer | 转换、清洗、切块 |
| DocumentWriter      | 写入目标存储     |

典型流程：

```
PDF
 ↓
DocumentReader
 ↓
List<Document>
 ↓
DocumentTransformer
 ↓
List<Document>
 ↓
DocumentWriter
 ↓
VectorStore
```

## **90. DocumentReader 如何从 PDF、Word 和 TXT 等文件中读取内容？**

不同格式通常需要不同解析器。

例如：

- PDF：PDF 解析器。
- Word：Office 文档解析器。
- TXT：文本读取器。

读取完成后统一转换为 Document。

需要注意，DocumentReader 本身并不意味着 Spring AI 对所有文件格式都提供统一的内置解析实现，具体需要选择相应的 Reader 或集成第三方解析库。

## **91. DocumentTransformer 如何完成清洗、切块和 Metadata 处理？**

它接收 Document 列表，输出处理后的 Document 列表。

典型操作：

- 去除无效字符。
- 清理页眉页脚。
- 合并不合理的换行。
- 文本切块。
- 添加页码。
- 保留文档来源。
- 添加设备型号等业务 Metadata。

## **92. TokenTextSplitter 的作用是什么？**

TokenTextSplitter 是 Spring AI 提供的文本切分工具。

它按照 Token 相关的切分策略，将较长 Document 拆分为多个较小的 Document。

主要解决：

- 文档过长。
- Embedding 输入限制。
- 检索粒度过大。
- 模型上下文容量限制。

它并不等于理解所有业务语义的智能切块器。

## **93. 为什么 Document 的 Metadata 应该在 Chunk 切分过程中得到保留？**

因为 Chunk 需要能够追溯到原始文档。

例如：

```
原始文档：
维修手册.pdf

Chunk：
设备 E102 故障处理方法

Metadata：
documentId = DOC001
page = 12
version = v2
deviceModel = XYZ-200
```

如果不保留 Metadata，就难以实现引用、权限过滤和版本管理。

## **94. DocumentWriter 如何将处理后的 Document 写入 VectorStore？**

DocumentWriter 负责把处理后的 Document 写入目标存储。

VectorStore 可以作为 DocumentWriter 使用：

```
vectorStore.add(documents);
```

具体实现负责向量化和持久化。

## **95. 如何使用 ETL Pipeline 串联文档读取、转换和写入？**

核心思想是：

```
List<Document> documents =
        reader.get();

List<Document> chunks =
        transformer.apply(documents);

writer.accept(chunks);
```

Spring AI 的 ETL 相关组件也支持通过 Pipeline 方式组织这些步骤。

具体方法签名需要结合使用的 Reader、Transformer 和 Writer 实现。

## **96. ETL Pipeline 与在线 RAG 查询链路有什么区别？**

| ETL Pipeline    | 在线 RAG           |
| --------------- | ------------------ |
| 离线或后台执行  | 用户请求时执行     |
| 处理原始文档    | 处理用户 Query     |
| 生成 Chunk 向量 | 生成 Query 向量    |
| 写入知识库      | 检索知识库         |
| 关注入库完整性  | 关注响应质量和延迟 |

两条链路共同构成完整 RAG 系统。

## **97. 如何通过自定义 DocumentTransformer 实现企业级文档清洗？**

可以实现自定义 Transformer：

```
public class BusinessDocumentTransformer
        implements DocumentTransformer {

    @Override
    public List<Document> transform(
            List<Document> documents) {

        // 清洗文本
        // 添加业务 Metadata
        // 执行切块
        // 保留来源信息

        return documents;
    }
}
```

具体接口签名应以 Spring AI 1.1.8 为准。

企业级清洗还应该考虑表格、图片、公式、页眉页脚和文档结构。

## **98. 如何处理大文件解析、批量导入和部分失败？**

建议设计异步任务：

```
文件上传
   ↓
任务创建
   ↓
文件解析
   ↓
分批切块
   ↓
分批 Embedding
   ↓
分批写入
   ↓
任务完成
```

每个任务保存：

- TaskId。
- DocumentId。
- 当前处理阶段。
- 成功 Chunk 数。
- 失败 Chunk 数。
- 错误信息。
- 重试次数。

通过任务幂等和断点恢复处理部分失败。

# **第八阶段：Advisor API 与 RAG 编排**

## **99. 什么是 Advisor？它在 ChatClient 调用链中处于什么位置？**

Advisor 是 Spring AI 提供的调用增强机制。

它可以在模型调用前后介入处理。

例如：

```
ChatClient
    ↓
Advisor Chain
    ↓
ChatModel
    ↓
Advisor 后处理
    ↓
返回结果
```

可以用于 RAG、日志、对话记忆和上下文处理。

## **100. Advisor API 主要解决什么问题？**

主要解决模型调用过程中横切逻辑难以复用的问题。

例如：

- 请求日志。
- Prompt 增强。
- RAG 检索。
- ChatMemory。
- 模型响应处理。
- 调用链追踪。

避免把所有增强逻辑都写在业务 Service 中。

## **101. CallAdvisor、StreamAdvisor 和 Advisor Chain 分别承担什么职责？**

- CallAdvisor：处理同步调用链。
- StreamAdvisor：处理流式调用链。
- Advisor Chain：串联多个 Advisor。

同步和流式调用的执行模型不同，不能认为只实现同步 Advisor 就能自动适配流式请求。

## **102. Advisor 如何在模型调用前修改 Prompt？**

Advisor 可以拦截请求，在调用下一个执行节点之前修改请求参数。

例如：

```
原始问题
   ↓
Advisor
   ↓
检索知识库
   ↓
追加上下文
   ↓
构建新 Prompt
   ↓
ChatModel
```

QuestionAnswerAdvisor 就属于这种典型思路。

## **103. Advisor 如何在模型调用后处理响应？**

Advisor 可以在模型返回后执行处理。

例如：

- 记录耗时。
- 提取 Metadata。
- 记录 Token Usage。
- 处理响应内容。
- 统一日志。

但涉及引用真实性和权限的校验，最好由独立的业务后处理组件负责。

## **104. 多个 Advisor 组成执行链时，执行顺序为什么很重要？**

因为不同 Advisor 之间可能存在输入输出依赖。

例如：

```
日志 Advisor
    ↓
ChatMemory Advisor
    ↓
RAG Advisor
    ↓
ChatModel
```

如果先执行 RAG 再处理历史消息，可能导致检索 Query 不完整。

实际执行顺序需要结合具体 Advisor 实现和调用链规则确认。

## **105. defaultAdvisors() 与 advisors() 有什么区别？**

- `defaultAdvisors()`：为 ChatClient 设置默认 Advisor。
- `advisors()`：为当前请求设置 Advisor。

前者适合公共能力，后者适合请求级定制。

例如，某个请求需要额外启用知识检索，可以在该请求上配置专门的 Advisor。

## **106. 什么是 QuestionAnswerAdvisor？它如何实现基础 RAG？**

QuestionAnswerAdvisor 是 Spring AI 提供的基础 RAG Advisor。

它将 VectorStore 检索与 ChatClient 调用结合。

典型流程：

```
用户问题
   ↓
QuestionAnswerAdvisor
   ↓
VectorStore
   ↓
检索相关 Document
   ↓
上下文注入 Prompt
   ↓
ChatModel
   ↓
生成答案
```

## **107. QuestionAnswerAdvisor 如何将 VectorStore 检索结果注入 Prompt？**

它根据用户问题执行向量检索，再将检索结果作为上下文信息加入模型请求。

模型根据用户问题和检索到的知识生成答案。

这让开发者不需要自己编写全部基础检索增强逻辑。

## **108. 使用 QuestionAnswerAdvisor 实现 RAG 有哪些优点和限制？**

优点：

- 接入简单。
- 代码量少。
- Spring AI 集成度高。
- 适合基础 RAG。

限制：

- 复杂权限控制需要额外设计。
- 文档版本治理需要额外设计。
- 复杂上下文组装需要自定义。
- 多阶段检索和重排序可能需要扩展。
- 引用真实性需要额外校验。

## **109. QuestionAnswerAdvisor 与手动编排 Retriever、Context Assembly 和 ChatClient 有什么区别？**

| QuestionAnswerAdvisor | 手动编排         |
| --------------------- | ---------------- |
| 自动完成基础检索增强  | 自己控制每个步骤 |
| 开发成本低            | 开发成本高       |
| 适合标准 RAG          | 适合复杂企业 RAG |
| 扩展控制相对有限      | 灵活度高         |

对于制造设备故障知识库，我更倾向于复杂业务采用手动编排。

## **110. 什么是 RetrievalAugmentationAdvisor？它与 QuestionAnswerAdvisor 有什么区别？**

RetrievalAugmentationAdvisor 是 Spring AI 提供的可组合检索增强框架。

它允许通过不同组件组合检索流程，例如：

- Query Transform。
- Query Expansion。
- Document Retrieval。
- Document Post Processing。
- Contextual Query Augmentation。

QuestionAnswerAdvisor 更偏向于基础向量检索增强。

RetrievalAugmentationAdvisor 更适合需要组合多个检索步骤的场景。

## **111. 如何通过 Advisor 实现 RAG 检索、上下文增强和模型生成？**

可以通过 RetrievalAugmentationAdvisor 组合检索组件。

也可以通过 QuestionAnswerAdvisor 实现基础流程。

对于复杂企业系统，建议把权限、文档版本、上下文预算和引用校验放在明确的业务组件中，不要过度依赖一个 Advisor。

## **112. 如何通过自定义 Advisor 实现请求日志、权限检查和上下文处理？**

可以实现自定义 Advisor：

```
public class AuditAdvisor
        implements CallAdvisor {

    // 实现请求前处理
    // 调用下一个执行节点
    // 执行响应后处理
}
```

实际 1.1.8 的方法签名、顺序和接口包路径应以 Javadoc 为准。

需要注意：权限检查应使用可信的服务端身份信息，不能只依据 Prompt 或客户端参数。

## **113. 如何避免多个 Advisor 之间发生参数覆盖和上下文冲突？**

主要措施：

1. 统一 Advisor 职责。
2. 明确执行顺序。
3. 统一上下文变量命名。
4. 区分默认参数和请求参数。
5. 避免多个 Advisor 修改同一个变量。
6. 建立 Advisor Chain 集成测试。

# **第九阶段：ChatMemory API 与多轮对话**

## **114. 为什么 ChatModel 本身不能自动记住之前的对话？**

ChatModel 本质上是无状态的模型调用接口。

每次调用时，它根据当前收到的 Prompt 生成响应，并不会自动保存之前的请求和回答。

例如：

第一次提问：

```
用户：我的设备型号是 XYZ-200。
助手：好的。
```

第二次提问：

```
用户：它的 E102 故障怎么处理？
```

如果没有传入历史消息，模型可能不知道“它”指的是 XYZ-200。

因此，需要通过 ChatMemory 管理历史消息，并在下一次请求时重新注入相关上下文。

## **115. 什么是 ChatMemory？它与普通数据库存储有什么区别？**

ChatMemory 是 Spring AI 提供的对话记忆抽象。

它负责管理模型后续调用需要使用的消息。

普通数据库可以保存完整聊天记录，而 ChatMemory 更关注哪些消息应该进入下一次模型请求。

| 对比     | ChatMemory     | 普通聊天记录     |
| -------- | -------------- | ---------------- |
| 目标     | 维护对话上下文 | 完整记录对话     |
| 数据     | 选定的历史消息 | 所有历史消息     |
| 主要用途 | 模型上下文构建 | 审计、展示、追溯 |
| 管理策略 | 窗口、数量等   | 业务保存策略     |

企业项目建议将两者分开设计。

## **116. ChatMemory、ChatMemoryRepository 和 ChatMemoryAdvisor 分别承担什么职责？**

三者分工：

| 组件                 | 职责                         |
| -------------------- | ---------------------------- |
| ChatMemory           | 管理对话记忆                 |
| ChatMemoryRepository | 保存和读取消息               |
| ChatMemoryAdvisor    | 将记忆接入 ChatClient 调用链 |

关系：

```
ChatClient
    ↓
ChatMemoryAdvisor
    ↓
ChatMemory
    ↓
ChatMemoryRepository
    ↓
数据库 / 内存
```

## **117. MessageWindowChatMemory 如何管理对话历史？**

MessageWindowChatMemory 采用滑动窗口管理历史消息。

例如：

```
ChatMemory memory = MessageWindowChatMemory.builder()
        .maxMessages(10)
        .build();
```

当消息超过窗口限制时，删除较早的消息，保留较新的上下文。

Spring AI 1.1.x 默认窗口大小为 20 条消息。

需要注意，它保留 SystemMessage，并且清理时会考虑完整对话轮次，不能简单理解成每次机械删除一条消息。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home+1



## **118. 如何通过 ChatMemory.CONVERSATION_ID 区分不同用户和会话？**

通过 Conversation ID 标识对话。

例如：

```
String conversationId = "conversation-001";

String answer = chatClient.prompt()
        .user("我的设备型号是 XYZ-200")
        .advisors(a -> a.param(
                ChatMemory.CONVERSATION_ID,
                conversationId
        ))
        .call()
        .content();
```

每个独立会话使用不同 ID。

生产环境应该由服务端根据当前认证用户和会话关系确定 ID，而不是直接信任客户端传入的任意 ID。

## **119. MessageChatMemoryAdvisor 如何将历史消息加入模型 Prompt？**

它在 ChatClient 调用过程中：

1. 根据 Conversation ID 获取历史消息。
2. 将历史消息加入当前模型请求。
3. 调用 ChatModel。
4. 将本轮交互消息写回 ChatMemory。

示例：

```
ChatMemory memory =
        MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();

ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory)
                        .build()
        )
        .build();
```

这样可以实现多轮对话。

## **120. VectorStoreChatMemoryAdvisor 与 MessageChatMemoryAdvisor 有什么区别？**

| MessageChatMemoryAdvisor | VectorStoreChatMemoryAdvisor |
| ------------------------ | ---------------------------- |
| 基于消息窗口             | 基于向量检索                 |
| 主要保留近期消息         | 根据语义召回历史信息         |
| 适合连续对话             | 适合长期语义记忆             |
| 更接近短期记忆           | 更接近长期记忆               |

例如：

用户几天前提到设备型号，今天再次询问相关故障。

VectorStoreChatMemoryAdvisor 可以根据语义检索相关历史内容。

但是，长期记忆不能替代业务数据库中的权威设备档案。

## **121. InMemoryChatMemoryRepository 与 JDBC、Redis 等持久化实现有什么区别？**

| 实现                          | 特点                     |
| ----------------------------- | ------------------------ |
| InMemoryChatMemoryRepository  | 内存存储，重启后丢失     |
| JdbcChatMemoryRepository      | 关系数据库持久化         |
| RedisChatMemoryRepository     | Redis 持久化及低延迟访问 |
| CassandraChatMemoryRepository | 分布式持久化             |

生产环境应根据持久化要求、部署架构、查询方式和成本选择。

特别注意：需要结合具体 Spring AI 1.1.8 实现验证 Tool Calling 消息的持久化支持，不能仅仅因为 Repository 能保存普通 Message，就认为所有工具调用中间消息都能完整保存。

## **122. 为什么多轮对话需要限制历史消息数量？**

因为模型上下文窗口有限，而且历史消息越多，Token 成本越高。

无限制传入历史消息可能造成：

- 请求成本增加。
- 响应时间变长。
- 重要信息被淹没。
- 超出模型上下文限制。
- 历史错误信息持续传播。

可以通过窗口截断、摘要压缩和相关历史检索控制上下文规模。

## **123. 如何防止不同用户之间发生会话记忆串用？**

这是企业应用的重要安全问题。

主要措施：

1. 每个用户拥有独立的身份标识。
2. 每个会话使用独立 Conversation ID。
3. 服务端校验用户与会话的归属关系。
4. 查询历史消息时增加租户和用户隔离。
5. 禁止客户端任意访问其他用户的 Conversation ID。
6. 对会话读取和删除操作进行审计。

核心原则：

**Conversation ID 不是权限凭证，真正的权限必须由服务端校验。**

## **124. ChatMemory 与 RAG 知识库检索有什么区别？**

| ChatMemory           | RAG 知识库               |
| -------------------- | ------------------------ |
| 保存对话历史         | 保存业务知识             |
| 来源是用户与模型交互 | 来源是业务文档           |
| 解决上下文连续性     | 解决外部知识获取         |
| 主要服务于当前会话   | 服务于多个用户和业务请求 |

例如：

用户之前说过设备型号 XYZ-200，这是 ChatMemory。

维修手册记载 E102 故障的处理步骤，这是 RAG 知识库。

不能把用户对话中的陈述直接当作已经验证的维修知识。

## **125. 如何将 ChatMemory 与 QuestionAnswerAdvisor 组合实现多轮 RAG 问答？**

可以将两个 Advisor 组合到 ChatClient。

```
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory)
                        .build(),
                QuestionAnswerAdvisor.builder(vectorStore)
                        .build()
        )
        .build();
```

请求：

```
String answer = client.prompt()
        .user("设备 E102 应该如何处理？")
        .advisors(a -> a.param(
                ChatMemory.CONVERSATION_ID,
                conversationId
        ))
        .call()
        .content();
```

执行时，ChatMemory 负责历史消息管理，QuestionAnswerAdvisor 负责知识检索增强。

需要特别注意：Advisor 顺序会影响 Query 是否能够使用历史上下文。对于“它为什么报警”这样的指代问题，不能假设简单组合两个 Advisor 就一定会先完成 Query Rewrite。复杂场景应当显式编排历史提取、Query 改写和 RAG 检索。

# **第十阶段：Tool Calling、MCP 与扩展 API**

## **126. 什么是 Tool Calling？它解决了大模型无法直接访问外部系统的什么问题？**

Tool Calling 是让大模型能够提出调用外部工具请求的机制。

大模型本身不能直接访问企业数据库、工单系统或设备管理平台。

通过 Tool Calling，可以让模型根据用户意图选择合适的工具，由 Java 应用执行真实业务操作。

例如：

```
用户：查询设备 XYZ-200 当前状态
                 ↓
              ChatModel
                 ↓
        请求调用 getDeviceStatus
                 ↓
             Java Tool
                 ↓
           DeviceService
                 ↓
             数据库
                 ↓
          返回设备状态
                 ↓
             ChatModel
                 ↓
             生成答案
```

核心区别：模型负责决定是否调用工具，Java 应用负责真正执行工具。

## **127. Spring AI 中如何通过 @Tool 将 Java 方法暴露给模型？**

通过 `@Tool` 标注 Java 方法。

例如：

```
@Component
public class DeviceTools {

    private final DeviceService deviceService;

    public DeviceTools(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Tool(description = "根据设备编号查询设备运行状态")
    public String getDeviceStatus(
            @ToolParam(description = "设备编号")
            String deviceCode) {

        return deviceService.getDeviceStatus(deviceCode);
    }
}
```

然后注册工具：

```
String answer = chatClient.prompt()
        .user("查询设备 XYZ-200 的状态")
        .tools(deviceTools)
        .call()
        .content();
```

Spring AI 会根据方法签名和注解生成工具定义，供模型选择。

## **128. ToolCallback 和 ToolDefinition 分别承担什么职责？**

| 组件           | 职责                         |
| -------------- | ---------------------------- |
| ToolDefinition | 描述工具名称、用途和输入参数 |
| ToolCallback   | 封装工具定义和实际执行逻辑   |

例如：

```
ToolDefinition
    ├── name
    ├── description
    └── input schema

ToolCallback
    ├── ToolDefinition
    ├── ToolMetadata
    └── call()
```

ToolDefinition 告诉模型工具如何使用，ToolCallback 负责真正执行。

## **129. 模型请求调用工具与 Java 应用实际执行工具有什么区别？**

模型输出 Tool Call 只是一个调用请求，不代表真实业务已经执行。

例如模型返回：

```
{
  "name": "getDeviceStatus",
  "arguments": {
    "deviceCode": "XYZ-200"
  }
}
```

之后由 Spring AI 的工具执行机制找到对应 ToolCallback，再执行 Java 方法。

所以：

- 模型：提出调用意图。
- Java：执行工具逻辑。
- 业务系统：完成实际数据访问或业务操作。

## **130. Tool Calling 的完整执行流程是什么？**

```
用户问题
   ↓
ChatClient
   ↓
ChatModel
   ↓
模型判断是否需要工具
   ↓
返回 Tool Call 请求
   ↓
Spring AI 分发 ToolCallback
   ↓
执行 Java 方法
   ↓
返回 Tool Result
   ↓
模型继续生成
   ↓
最终答案
```

这是典型的工具调用闭环。

## **131. Tool Calling 与普通 Java 方法调用有什么区别？**

| Tool Calling             | 普通 Java 方法调用     |
| ------------------------ | ---------------------- |
| 模型参与工具选择         | 程序员明确指定         |
| 参数由模型根据上下文生成 | 参数由 Java 代码提供   |
| 需要工具定义             | 直接调用方法           |
| 存在模型不确定性         | 调用路径通常确定       |
| 需要额外权限和安全治理   | 使用 Java 业务权限体系 |

Tool Calling 不能替代正常业务服务调用。

核心业务仍然应该放在 Java Service 中。

## **132. 如何通过 ToolContext 向工具传递业务上下文？**

ToolContext 用于传递不应该交给模型生成的可信业务上下文。

例如：

```
@Tool(description = "查询当前租户的设备信息")
public String getDevice(
        String deviceCode,
        ToolContext context) {

    String tenantId = (String) context.getContext()
            .get("tenantId");

    return deviceService.getDevice(
            tenantId,
            deviceCode
    );
}
```

调用时：

```
chatClient.prompt()
        .user("查询 XYZ-200")
        .tools(deviceTools)
        .toolContext(Map.of(
                "tenantId", authenticatedTenantId
        ))
        .call()
        .content();
```

这里的 tenantId 应由认证和授权体系产生，而不是让模型自行决定。

ToolContext 的业务上下文不会作为普通工具参数发送给模型。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home



## **133. 如何限制模型可以调用的工具，防止越权操作？**

需要多层控制：

1. 只注册当前业务允许的工具。
2. 按用户角色限制工具集合。
3. 在工具方法内部执行权限校验。
4. 对高风险操作增加人工确认。
5. 对关键操作记录审计日志。
6. 设置参数白名单和业务约束。
7. 对工具执行增加超时和限流。

例如，模型可以查询工单，但不能因为用户说“删除所有工单”就直接获得删除权限。

## **134. 什么是 MCP？它解决了什么问题？**

MCP 全称 Model Context Protocol，即模型上下文协议。

它用于标准化 AI 应用与外部工具、资源和提示模板之间的交互。

主要解决不同 AI 客户端与外部服务之间集成方式不统一的问题。

传统模式：

```
AI 应用 A → 自定义接口 A
AI 应用 B → 自定义接口 B
AI 应用 C → 自定义接口 C
```

MCP 模式：

```
AI 客户端
    ↓
MCP Client
    ↓
MCP Protocol
    ↓
MCP Server
    ↓
Tools / Resources / Prompts
```

## **135. MCP Client、MCP Server 和 Tool 之间是什么关系？**

| 组件       | 职责                        |
| ---------- | --------------------------- |
| MCP Client | 连接 MCP Server             |
| MCP Server | 对外提供标准化能力          |
| Tool       | MCP Server 暴露的可调用工具 |

例如：

```
Spring AI 应用
      ↓
MCP Client
      ↓
设备管理 MCP Server
      ↓
查询设备状态 Tool
      ↓
设备管理系统
```

MCP Server 负责将已有业务能力包装为标准化接口。

## **136. Spring AI 如何接入 MCP Server 提供的工具？**

Spring AI 提供 MCP Client Starter。

基本步骤：

1. 引入 MCP Client 依赖。
2. 配置 MCP Server 连接。
3. 建立 MCP Client。
4. 获取远程 MCP Server 提供的工具。
5. 将工具交给 ChatClient 使用。

概念关系：

```
ChatClient
    ↓
MCP Tool Adapter
    ↓
MCP Client
    ↓
MCP Server
    ↓
业务工具
```

Spring AI 1.1.x 支持多种 MCP 传输方式，包括 STDIO、SSE 和 Streamable HTTP 等，具体取决于所选 Starter 和配置。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home



## **137. Tool Calling 与 MCP 有什么区别和联系？**

这是高频面试题。

| Tool Calling               | MCP                     |
| -------------------------- | ----------------------- |
| 模型调用工具的机制         | 外部能力交互协议        |
| 关注模型如何请求工具       | 关注工具如何标准化提供  |
| 可以直接调用本地 Java 方法 | 可以调用远程 MCP Server |
| 不强制使用 MCP             | 可以通过 MCP 提供 Tool  |

两者可以结合使用：

```
ChatModel
    ↓
Tool Calling
    ↓
Spring AI
    ↓
MCP Client
    ↓
MCP Server
    ↓
外部业务系统
```

## **138. 如何将 RAG 检索能力与 Tool Calling 结合？**

可以将知识检索封装成一个 Tool。

例如：

```
@Tool(description = "根据设备型号和故障代码检索维修知识")
public String searchKnowledge(
        String deviceModel,
        String faultCode) {

    return knowledgeService.search(
            deviceModel,
            faultCode
    );
}
```

模型根据用户问题决定是否调用该工具。

但如果 RAG 是每次请求都必须执行的核心流程，我更建议由 RagService 显式编排，而不是完全交给模型决定是否检索。

这样能够保证检索策略、权限和引用校验的确定性。

## **139. 在制造设备故障知识库与工单助手中，哪些能力适合设计成 Tool？**

建议：

| Tool                  | 作用         | 风险 |
| --------------------- | ------------ | ---- |
| getDeviceStatus       | 查询设备状态 | 低   |
| getFaultCodeInfo      | 查询故障代码 | 低   |
| searchRepairKnowledge | 查询维修知识 | 低   |
| getWorkOrder          | 查询工单     | 中   |
| createWorkOrder       | 创建工单     | 中   |
| assignWorkOrder       | 分配工单     | 中   |
| closeWorkOrder        | 关闭工单     | 高   |
| executeDeviceCommand  | 执行设备操作 | 极高 |

高风险 Tool 必须增加权限、审批、幂等和操作审计。

# **第十一阶段：自动配置、配置管理与可观测性**

## **140. Spring AI 的自动配置机制如何创建 ChatModel、EmbeddingModel 和 VectorStore？**

主要通过 Spring Boot Starter 和 AutoConfiguration。

例如：

```
引入模型 Starter
       ↓
配置 application.yml
       ↓
Spring Boot 加载自动配置
       ↓
创建 ChatModel
       ↓
创建 ChatClient.Builder
       ↓
业务代码注入
```

EmbeddingModel 和 VectorStore 也是类似机制。

需要注意，VectorStore 还需要对应数据库 Starter、数据库连接和向量表配置。

## **141. 如何通过 application.yml 配置模型供应商、API Key 和模型名称？**

例如：

```
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY}
      chat:
        options:
          model: ${AI_MODEL}
          temperature: 0.2
```

不同供应商具有不同的配置前缀和属性结构。

实际配置必须与对应的 Starter 保持一致。

## **142. 如何通过环境变量和外部配置中心管理敏感模型配置？**

建议采用：

- 环境变量。
- Spring Cloud Config。
- Nacos 配置中心。
- Kubernetes Secret。
- 云平台密钥管理服务。

例如：

```
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY}
```

API Key 不应提交到 Git，也不应直接写在 Java 代码中。

## **143. 如何避免在 Java 业务代码中硬编码模型名称和 API Key？**

通过配置类集中管理：

```
app:
  ai:
    chat:
      provider: deepseek
      model: ${AI_MODEL}
      base-url: ${AI_BASE_URL}
      api-key: ${AI_API_KEY}
```

业务层只依赖 ChatClient 或模型抽象。

模型配置与业务代码解耦。

对于多模型场景，可以设计独立的模型路由组件。

## **144. 如何通过 @ConditionalOnMissingBean 自定义或替换 Spring AI 默认组件？**

`@ConditionalOnMissingBean` 是 Spring Boot 条件注解。

当容器中不存在指定类型的 Bean 时，才创建默认 Bean。

例如：

```
@Bean
@ConditionalOnMissingBean
public MyService myService() {
    return new MyService();
}
```

在 Spring AI 自动配置中，可以通过类似机制允许开发者替换默认实现。

但具体某个组件是否支持覆盖，需要查看对应自动配置类上的条件注解，不能认为所有 Bean 都可以无条件替换。

## **145. 如何查看 Spring AI 自动配置是否生效？**

常见方法：

1. 启动日志。
2. Spring Boot Actuator。
3. `conditions` 端点。
4. Bean 列表。
5. ApplicationContext 测试。
6. 检查模型服务连接。

例如：

```
management:
  endpoints:
    web:
      exposure:
        include: health,info,beans,conditions
```

生产环境应限制 Actuator 端点访问权限，避免暴露内部 Bean 和配置细节。

## **146. Spring AI 如何通过 Micrometer 提供模型调用指标？**

Spring AI 可以通过 Micrometer Observation 机制记录模型调用相关观测数据。

主要包括：

- 模型调用次数。
- 调用耗时。
- 成功与失败。
- Token 使用情况（支持时）。

可以将指标导出到 Prometheus，再通过 Grafana 展示。

## **147. 如何追踪 ChatClient、ChatModel、EmbeddingModel 和 VectorStore 的调用耗时？**

可以使用 Micrometer Observation 和 OpenTelemetry。

建议按阶段记录：

```
rag.request
    ├── query.rewrite
    ├── embedding
    ├── vector.search
    ├── context.assembly
    ├── chat.generation
    └── citation.validation
```

每个阶段记录：

- TraceId。
- SpanId。
- 开始时间。
- 结束时间。
- 耗时。
- 执行结果。
- 异常类型。

## **148. 如何记录模型 Token 使用量、调用次数和异常信息？**

建立统一模型调用日志。

例如：

```
{
  "traceId": "trace-001",
  "model": "configured-model",
  "operation": "chat",
  "inputTokens": 1200,
  "outputTokens": 350,
  "latencyMs": 1800,
  "status": "SUCCESS"
}
```

实际 Token 数应尽量使用供应商返回的 Usage。

不能将估算 Token 和真实 Token 混为一谈。

## **149. 为什么生产环境不能无差别记录完整 Prompt、用户隐私和敏感上下文？**

因为 Prompt 可能包含：

- 用户个人信息。
- 企业商业秘密。
- 设备运行数据。
- 内部维修手册。
- 认证信息。
- 业务系统敏感参数。

无差别记录会造成数据泄露风险。

建议：

1. 默认记录摘要和元数据。
2. 对敏感字段脱敏。
3. 控制日志访问权限。
4. 设置日志保留期限。
5. 避免记录 API Key。
6. 对 Prompt 内容实施分级审计。

## **150. 如何通过 TraceId 定位一次完整 RAG 请求的性能问题？**

使用 TraceId 串联整个调用过程。

例如：

```
TraceId: trace-001

Controller          20ms
Query Rewrite      200ms
Embedding          100ms
Vector Search       80ms
Context Assembly    15ms
ChatModel         1800ms
Post Process        20ms
------------------------
Total             2235ms
```

通过耗时分布判断瓶颈。

如果 ChatModel 占比最高，就优先分析模型服务延迟。

如果 Vector Search 占比最高，就分析索引、过滤条件、数据库负载和连接池。

# **第十二阶段：核心 API 综合应用与工程设计**

## **151. 如何使用 ChatClient、ChatModel 和 EmbeddingModel 构建一个最小 AI 问答服务？**

先定义依赖：

```
@Service
public class AiChatService {

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    public AiChatService(
            ChatClient chatClient,
            EmbeddingModel embeddingModel) {

        this.chatClient = chatClient;
        this.embeddingModel = embeddingModel;
    }

    public String chat(String question) {

        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    public float[] embedding(String text) {

        return embeddingModel.embed(text);
    }
}
```

ChatClient 负责问答，EmbeddingModel 负责向量化。

ChatModel 由 ChatClient 底层使用。

## **152. 如何使用 DocumentReader、DocumentTransformer 和 VectorStore 构建知识入库流程？**

流程：

```
上传文件
   ↓
DocumentReader
   ↓
DocumentTransformer
   ↓
DocumentWriter
   ↓
VectorStore
   ↓
PostgreSQL + pgvector
```

Java 业务层可以设计一个 KnowledgeIngestionService，专门负责整个入库任务。

## **153. 如何使用 EmbeddingModel、VectorStore 和 SearchRequest 实现完整检索流程？**

```
SearchRequest request = SearchRequest.builder()
        .query("设备 E102 如何维修")
        .topK(5)
        .similarityThreshold(0.75)
        .build();

List<Document> documents =
        vectorStore.similaritySearch(request);
```

这里 EmbeddingModel 通常由 VectorStore 内部调用。

如果直接调用 EmbeddingModel，则可以显式获得向量，再结合特定数据库接口执行检索。

## **154. 如何将 Retriever、Context Assembly、Prompt 和 ChatClient 组合成可控的 RAG 调用链？**

建议设计独立组件：

```
RagController
     ↓
RagService
     ↓
QueryProcessor
     ↓
Retriever
     ↓
ContextAssembler
     ↓
PromptBuilder
     ↓
ChatClient
     ↓
AnswerPostProcessor
```

代码职责：

- QueryProcessor：处理用户问题。
- Retriever：检索知识。
- ContextAssembler：筛选和组织知识。
- PromptBuilder：构建最终 Prompt。
- ChatClient：调用模型。
- AnswerPostProcessor：校验结果和引用。

这种方式比把全部业务逻辑写在 Controller 中更容易维护和测试。

## **155. 如何根据业务需求选择 Advisor 自动编排还是手动编排？**

| 场景               | 推荐方式                   |
| ------------------ | -------------------------- |
| 简单知识问答       | QuestionAnswerAdvisor      |
| 基础多轮问答       | ChatMemoryAdvisor          |
| 标准日志增强       | Advisor                    |
| 复杂 Query Rewrite | 手动编排或高级检索增强组件 |
| 多租户权限过滤     | 显式业务编排               |
| 多版本知识治理     | 手动编排                   |
| 复杂上下文预算     | 手动编排                   |
| 引用验证           | 独立后处理                 |

选择原则：

简单场景优先使用框架能力，复杂业务保留明确的控制权。

## **156. 如何设计 Spring AI 核心 API 的依赖注入关系，避免组件职责混乱？**

建议：

```
Controller
    ↓
Application Service
    ↓
RagService
    ├── QueryProcessor
    ├── Retriever
    ├── ContextAssembler
    ├── PromptBuilder
    ├── ChatClient
    └── CitationValidator

KnowledgeIngestionService
    ├── DocumentReader
    ├── DocumentTransformer
    └── VectorStore

基础设施层
    ├── ChatModel
    ├── EmbeddingModel
    ├── PostgreSQL
    └── Redis
```

Controller 不直接操作 VectorStore，也不直接承担模型调用和上下文拼装职责。

## **157. 如何为 ChatClient、EmbeddingModel 和 VectorStore 编写单元测试与集成测试？**

分层测试：

| 测试类型     | 验证目标              |
| ------------ | --------------------- |
| 单元测试     | Service 业务逻辑      |
| Mock 测试    | 模型调用交互          |
| 集成测试     | PostgreSQL + pgvector |
| 模型契约测试 | 模型请求和响应格式    |
| 端到端测试   | 完整 RAG 流程         |

例如：

```
@Mock
private ChatClient chatClient;
```

测试重点不只是验证方法是否执行，还应验证输入 Prompt、参数、异常处理和返回结构。

## **158. 如何使用 Mock 模型测试业务逻辑，避免测试过程依赖真实模型服务？**

可以对 ChatClient、EmbeddingModel 等依赖进行 Mock。

例如：

```
when(chatClient.prompt())
        .thenReturn(promptSpec);
```

然后模拟模型返回预设结果。

对于复杂场景，更推荐在应用层定义自己的模型调用接口，通过适配器隔离 Spring AI。

这样业务测试不需要访问真实模型服务，也不会产生 Token 费用。

## **159. Spring AI 核心 API 升级时，如何评估版本兼容性和潜在破坏性变更？**

主要检查：

1. Starter 依赖变化。
2. Java API 方法变化。
3. 自动配置属性变化。
4. Advisor 执行行为变化。
5. VectorStore 实现变化。
6. Prompt Template 行为变化。
7. ChatMemory 存储结构变化。
8. Tool Calling 和 MCP 协议兼容性。
9. Token Usage 和 Observability 变化。

建议通过：

- 固定版本。
- 阅读官方迁移指南。
- Maven 依赖树分析。
- 单元测试。
- 集成测试。
- 端到端回归。

不要只升级 Maven 版本后就认为项目兼容。

## **160. 如果让你基于 Spring AI 1.1.8 从零构建制造设备故障知识库与工单助手，你会如何选择和组合核心 API？**

**参考口述答案：**

如果让我从零构建，我会采用分层架构，将文档入库、RAG 检索、对话管理、模型生成和业务工具调用拆分为独立模块。

### **第一，模型接入层**

使用 Spring AI Starter 接入模型。

主要组件：

- ChatModel。
- ChatClient。
- EmbeddingModel。

通过 application.yml 和环境变量管理模型配置，不在业务代码中硬编码模型名称和 API Key。

### **第二，知识入库层**

使用 Spring AI ETL Pipeline：

```
DocumentReader
     ↓
DocumentTransformer
     ↓
TokenTextSplitter
     ↓
EmbeddingModel
     ↓
PostgreSQLVectorStore
```

文档 Metadata 至少保留：

- tenantId。
- documentId。
- documentVersion。
- deviceModel。
- faultCode。
- pageNumber。
- documentStatus。

通过文档状态机、幂等和版本切换保证知识库一致性。

### **第三，RAG 检索层**

使用：

- EmbeddingModel。
- VectorStore。
- SearchRequest。
- PostgreSQL + pgvector。

实现设备型号、故障代码、租户和文档版本过滤。

复杂场景加入关键词检索和 Reranker。

### **第四，上下文与生成层**

使用 ChatClient 作为统一模型调用入口。

由独立组件完成：

- Query Rewrite。
- Retriever。
- Context Assembly。
- Token Budget。
- Prompt Builder。
- Citation Validator。

对于简单场景可以使用 QuestionAnswerAdvisor，对于复杂制造业务采用手动编排。

### **第五，多轮对话层**

使用 ChatMemory 和 MessageChatMemoryAdvisor 管理对话上下文。

通过 Conversation ID 实现会话隔离。

完整聊天记录另外持久化，不能把 ChatMemory 当成完整业务聊天记录。

### **第六，工具调用层**

使用 `@Tool` 将业务能力暴露给模型。

例如：

- 查询设备状态。
- 查询故障代码。
- 检索维修知识。
- 查询历史工单。
- 创建维修工单。

跨系统工具可以使用 MCP 进行标准化集成。

涉及创建、关闭工单和控制设备的高风险操作，需要权限校验和人工确认。

### **第七，生产治理层**

使用：

- Spring Boot。
- Micrometer。
- OpenTelemetry。
- PostgreSQL。
- pgvector。
- Redis。
- 统一异常处理。
- 限流、熔断和重试。

建立完整的 TraceId 追踪体系，记录各阶段耗时、Token 使用量、检索结果和模型异常。

### **最终架构**

```
                 前端用户
                    |
               RagController
                    |
                 RagService
                    |
        +-----------+-----------+
        |           |           |
   Query处理     ChatMemory   权限校验
        |           |           |
        +-----------+-----------+
                    |
                 Retriever
                    |
               VectorStore
                    |
              PostgreSQL
                 pgvector
                    |
              ContextAssembler
                    |
                PromptBuilder
                    |
                 ChatClient
                    |
                 ChatModel
                    |
             AnswerPostProcessor
                    |
             CitationValidator
                    |
                最终答案
                    |
                工单 Tools
                    |
              工单管理系统
```

**面试总结：**

我不会把 Spring AI 理解成简单的大模型 SDK，而是把它作为 Java AI 应用的基础开发框架。

通过 ChatClient 实现模型调用，通过 EmbeddingModel 和 VectorStore 实现知识检索，通过 ETL Pipeline 实现知识入库，通过 Advisors 和 ChatMemory 实现调用增强与对话管理，通过 Tool Calling 和 MCP 集成外部业务能力。

最终结合 Spring Boot 的工程能力，构建可维护、可扩展、可追踪、可测试的企业级 AI 应用。

# **五、Spring AI 1.1.8 版本特别注意事项**

这部分建议重点记忆，因为面试官可能会通过版本差异判断你是否真正使用过框架。

| 知识点                       | 1.1.8 学习时需要注意                 |
| ---------------------------- | ------------------------------------ |
| ChatMemory                   | 默认实现是 MessageWindowChatMemory   |
| 默认窗口                     | 20 条消息                            |
| MessageChatMemoryAdvisor     | 推荐使用                             |
| PromptChatMemoryAdvisor      | 从 1.1.3 起已弃用                    |
| VectorStoreChatMemoryAdvisor | 支持向量化历史记忆                   |
| RedisChatMemoryRepository    | 不要直接套用后续版本文档             |
| Tool Calling                 | 通过 ToolCallback 等机制实现         |
| MCP                          | 有独立 Starter 和 Client/Server 集成 |
| Observability                | 需要 Spring Boot Actuator            |
| Prompt 和 Completion 日志    | 默认不导出                           |

特别纠正三个容易混淆的地方：

1\. **MessageWindowChatMemory：** 1.1.8 的文档说明其默认窗口为 20 条消息，并保留 SystemMessage。不要把后续版本的所有窗口淘汰细节直接套用到 1.1.8。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home



2\. **ChatMemory Repository：** 1.1.8 应优先以该版本实际提供的 InMemory、JDBC、Cassandra、Neo4j 等实现为准，不要直接把后续版本的 RedisChatMemoryRepository 当成 1.1.8 内置能力。

3\. **Observability：** Spring AI 1.1.8 已支持 ChatClient、ChatModel、EmbeddingModel 和 VectorStore 的指标与追踪，但具体模型供应商的支持范围有所不同。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home



# **六、官方参考资料**

以下均采用与你的学习基线对应的 1.1 文档或 1.1.8 Javadoc。

**核心 API**

1. Spring AI Reference | Spring AI 官方参考文档：[https://docs.spring.io/spring-ai/reference/1.1/api/](https://docs.spring.io/spring-ai/reference/1.1/api/?utm_source=chatgpt.com)
2. Chat Client API | 聊天客户端 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html?utm_source=chatgpt.com)
3. Chat Models | 聊天模型 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html?utm_source=chatgpt.com)
4. Embedding Models | 嵌入模型 API：[https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html](https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html?utm_source=chatgpt.com)
5. Vector Databases | 向量数据库 API：[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html?utm_source=chatgpt.com)

**高级能力**

6. ETL Pipeline | ETL 数据处理管道：[https://docs.spring.io/spring-ai/reference/1.1/api/etl-pipeline.html](https://docs.spring.io/spring-ai/reference/1.1/api/etl-pipeline.html?utm_source=chatgpt.com)
7. Advisors API | 顾问 API：[https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html](https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html?utm_source=chatgpt.com)
8. Chat Memory | 对话记忆 API：[https://docs.spring.io/spring-ai/reference/1.1/api/chat-memory.html](https://docs.spring.io/spring-ai/reference/1.1/api/chat-memory.html?utm_source=chatgpt.com)
9. Tool Calling | 工具调用 API：[https://docs.spring.io/spring-ai/reference/1.1/api/tools.html](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html?utm_source=chatgpt.com)
10. MCP Overview | 模型上下文协议总览：[https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-overview.html](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-overview.html?utm_source=chatgpt.com)
11. Observability | 可观测性：[https://docs.spring.io/spring-ai/reference/1.1/observability/index.html](https://docs.spring.io/spring-ai/reference/1.1/observability/index.html?utm_source=chatgpt.com)

**Spring AI 1.1.8 Javadoc**

12. ChatClient API | 聊天客户端 API：[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html?utm_source=chatgpt.com)
13. ChatModel API | 聊天模型 API：[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html?utm_source=chatgpt.com)
14. EmbeddingModel API | 嵌入模型 API：[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html?utm_source=chatgpt.com)
15. VectorStore API | 向量存储 API：[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html?utm_source=chatgpt.com)
16. Document API | 文档 API：[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html?utm_source=chatgpt.com)

# **七、面试前必须形成的核心知识体系**

建议把 160 道题归纳成下面 12 个知识模块：

| 模块              | 必须能够讲清楚的内容                |
| ----------------- | ----------------------------------- |
| Model API         | ChatModel、EmbeddingModel           |
| ChatClient        | prompt、call、stream、entity        |
| Prompt            | Prompt、Message、Template           |
| Structured Output | BeanOutputConverter、DTO 校验       |
| Embedding         | 向量生成、维度、兼容性              |
| VectorStore       | add、delete、similaritySearch       |
| ETL               | Reader、Transformer、Writer         |
| Advisor           | 调用增强、Advisor Chain             |
| RAG               | QuestionAnswerAdvisor、手动编排     |
| Memory            | ChatMemory、Repository、Advisor     |
| Tool / MCP        | @Tool、ToolCallback、MCP            |
| 工程治理          | AutoConfiguration、Tracing、Metrics |

**最终学习目标：**

不仅能回答 Spring AI 有哪些 API，还能独立解释：

- 为什么需要这个 API？
- 它解决什么问题？
- 它的输入和输出是什么？
- 它与其他 API 如何协作？
- 在 Spring AI 1.1.8 中应该如何实现？
- 在制造设备知识库项目中应该放在哪个模块？

这才是真正具备 Java AI 后端工程师的 Spring AI 核心 API 能力。