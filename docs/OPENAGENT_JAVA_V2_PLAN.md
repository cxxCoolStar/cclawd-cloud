# OpenAgent Java V2 Agent 工具调用实施方案

> 版本：V2.0 方案稿  
> 编制日期：2026-07-16  
> 前置版本：当前 Java Chat MVP（README 中的 V0.2）  
> 前端策略：继续复用现有 FastClaw Next.js 前端，仅做协议兼容和工具过程展示  
> 关联文档：[OPENAGENT_JAVA_V1_PLAN.md](OPENAGENT_JAVA_V1_PLAN.md)、[PROJECT_REFACTORING_PLAN.md](PROJECT_REFACTORING_PLAN.md)  
> 评审状态：已对照 FastClaw/ragent 源码及当前代码库核查（2026-07-16），发现的问题与未完成重构的衔接见 [第 20 章](#20-评审发现的问题与重构衔接必读)，实施前必须先阅读该章

## 1. 版本定位

V2 的目标是把 OpenAgent 从“可以流式聊天的 Chatbot”升级为“可以自主选择工具并完成任务的 Agent”。

当前版本已经具备以下基础能力：

- Java 17、Spring Boot 3.5.7 和 Maven 多模块工程；
- SQLite 自动建库和默认 Provider、默认 Agent 初始化；
- OpenAI-compatible 模型流式调用；
- 会话、消息和最终事件持久化；
- 模型执行与浏览器 SSE 连接解耦，切换会话不会中断后台任务；
- 复用现有前端并直接进入默认 Agent 的 Chatbot 页面；
- 本地单用户模式，暂不校验权限。

V2 在此基础上增加下面的核心闭环：

```text
用户输入任务
  -> 加载 Agent 配置、上下文和可用工具
  -> 调用模型
  -> 模型返回 tool_calls
  -> 校验参数和工具策略
  -> 执行工具并持久化结果
  -> 将 tool result 追加到模型上下文
  -> 再次调用模型
  -> 得到最终回答
  -> 持久化消息和事件
  -> 前端展示工具执行过程和最终结果
```

V2 的判断标准不是“后端存在几个工具类”，而是模型能够自主选择工具、获得真实结果、继续推理并给出最终回答，整个过程可观察、可恢复、可测试。

### 1.1 双参考项目原则

V2 实施必须同时参考两个项目，但职责不同：

| 参考项目 | 路径 | 作用 |
|---|---|---|
| FastClaw | D:\resources\code\fastclaw | 产品行为、Agent 运行语义、工具/事件协议、前端兼容和异常边界的事实基线 |
| ragent | D:\resources\code\ragent | Java 分层、Controller/Service、异常、配置、线程池和日志风格的代码规范基线 |

实施时不得只看本文档后凭经验重新设计：

- 功能表现先阅读 FastClaw 对应实现和测试；
- Java 代码组织先阅读 ragent 对应模块和同类代码；
- FastClaw 与现有前端形成的字段、顺序和恢复语义优先保持兼容；
- ragent 的设计模式可以复用，具体依赖和基础设施不得机械照搬；
- 有意偏离参考实现时，必须记录原因、风险和测试证据。

### 1.2 FastClaw 行为基线

开发 V2 前必须阅读实际源码，而不是只阅读 FastClaw 的 README：

| 能力 | FastClaw 参考文件 | 必须保留的行为 |
|---|---|---|
| ReAct 主循环 | internal/agent/loop.go | 多轮循环、消息配对、循环保护和迭代上限后的最终交付 |
| 工具注册 | internal/agent/tools/registry.go | 只有已注册且启用的工具能暴露给模型 |
| 文件工具 | internal/agent/tools/file.go、file_test.go、apply_patch.go | 参数、结果文本、边界输入和补丁行为 |
| Workspace | internal/workspace/workspace.go、localfs.go | agent/session 作用域、相对路径和目录布局 |
| 事件与重连 | internal/agent/events.go、event_hub.go | 增量仅实时广播，最终事件先持久化，按 seq 去重 |
| HTTP 生命周期 | internal/setup/handlers.go | 模型运行脱离浏览器请求取消，断开只停止 SSE 转发 |
| OpenAI 协议 | internal/provider/openai.go、openai_*_test.go | tool call 流式分片聚合和 assistant 结构 |
| 异常 Tool Call | internal/agent/tool_recovery.go、tool_recovery_test.go | Tool Call 标记泄漏时的兼容和显示清理 |
| 前端事件 | web/src/lib/api.ts、web/src/components/chat-screen.tsx | 事件字段、多轮工具分组和历史重载兼容 |

以下属于 V2 必测行为：

1. 每个 assistant tool call ID 都必须有对应 tool result；孤立 tool call 会使后续模型请求协议非法。
2. 连续 3 次调用相同工具且 arguments 完全相同时触发循环保护。
3. 连续 3 轮工具全部失败时停止继续消耗工具预算，要求模型尽力回答。
4. 达到最大迭代数后，再进行一次不携带 tools 的最终总结；总结也失败时才使用固定兜底文本。
5. 模型同时返回正文和 tool calls 时，正文、assistant tool_calls、tool results 的顺序必须保留。
6. content_delta 不入库，最终 content 负责刷新和重连后的恢复。
7. POST 流与 GET 订阅可能收到相同最终事件，必须依赖持久化 seq 去重。
### 1.3 ragent Java 规范基线

V2 开发前应阅读以下代表性 Java 文件：

| 规范 | ragent 参考文件 | OpenAgent 应用方式 |
|---|---|---|
| 统一返回 | framework/.../convention/Result.java、framework/.../web/Results.java | ~~普通 JSON API 使用统一泛型返回~~ **已被 Phase 2 决策否决，见 20.2 问题 6：REST 直接返回 VO（fastclaw 形状），Result 不上 wire**；SSE 保留流式响应豁免 |
| 全局异常 | framework/.../web/GlobalExceptionHandler.java | 统一映射业务异常，Controller 不拼装异常响应 |
| Controller | bootstrap/.../admin/controller/DashboardController.java、knowledge/controller/* | 构造注入、薄 Controller、request/vo 独立类型 |
| Service | bootstrap/.../user/service/UserService.java、service/impl/UserServiceImpl.java | 接口与实现分离，校验和事务下沉 |
| AI 基础设施 | infra-ai/.../config/AIModelProperties.java、infra-ai/.../http/* | Provider 配置集中化，远程错误统一分类 |
| 线程池 | bootstrap/.../rag/config/ThreadPoolExecutorConfig.java | 按业务隔离、命名线程、显式队列和拒绝策略 |
| 日志 | 使用 @Slf4j 的 Service、Runner 和 Client | 结构化占位符日志，记录关联 ID，不记录密钥和完整工具结果 |

结合 OpenAgent 当前约束，以下内容不能照搬：

- 继续使用 JdbcTemplate/SQLite，不为了模仿 ragent 强制切换 MyBatis-Plus；
- 当前不引入 Sa-Token 和 TTL，但保留 userId 领域字段；
- 不复制 ragent 的许可证文件头或源代码正文，只参考结构和模式；
- Agent/工具线程池不得使用可能让 Web/SSE 线程执行长任务的 CallerRunsPolicy，应显式拒绝并返回服务繁忙；
- V2 配置统一使用 @ConfigurationProperties，不延续参考项目中少数 @Value 用法；
- 领域层不得依赖 ResponseStatusException 或其他 Spring Web 类型。

### 1.4 强制实施流程

每个 V2 里程碑必须执行：

1. **参考定位**：列出对应的 FastClaw 和 ragent 文件。
2. **行为清单**：从 FastClaw 提取正常、边界和失败行为，形成 characterization test。
3. **Java 设计**：按 ragent 风格确定接口、实现、DTO、异常和配置归属。
4. **先测协议**：Tool Call 分片、事件字段、配对关系、路径边界先写 fixture/单测。
5. **差异验证**：核对 FastClaw 与 OpenAgent 的事件顺序、历史结构和最终状态。
6. **真实模型验证**：fixture 通过后再用当前 kimi-k2.5 smoke test。
7. **评审记录**：PR 或实施记录增加“参考文件”和“有意差异”两节。

参考项目已有测试时，OpenAgent 至少覆盖相同风险，不能以语言不同为由省略。
## 2. 版本目标

### 2.1 产品目标

1. 用户可以直接在现有 Chatbot 页面向默认 Agent 下达需要使用工具的任务。
2. Agent 可以在一次运行中执行多轮 `model -> tool -> model` 循环。
3. 前端可以看到工具名称、参数、执行状态、结果摘要和最终回答。
4. 用户切换会话或关闭页面后，Agent 仍能继续执行；重新进入会话后能够看到已持久化结果。
5. 默认安装后无需进入管理后台即可体验 V2 能力。

### 2.2 工程目标

1. 让 `agent-core`、`infra-ai` 成为被真实业务消费的模块，而不是仅保留接口的空壳（`runtime-integration` 已在 Phase 0 删除，内置工具承载位置见 20.2 问题 1 待决策）。
2. Agent 循环不放在 Controller 或 SSE 线程中，由独立协调器和线程池管理生命周期。
3. 模型事件、Agent 事件和工具结果使用强类型对象，不继续扩大 `Map<String, Object>` 的使用范围。
4. 工具实现遵循统一注册、参数校验、超时、结果截断、审计和错误映射规范。
5. 数据库迁移只做增量变更，不破坏当前会话和消息数据。

## 3. V2 范围

### 3.1 必须交付

#### Agent 配置

- 默认 Agent 支持系统提示词；
- 支持配置模型、temperature、max tokens；
- 支持配置 `maxToolIterations`，默认 8（**有意偏离 FastClaw 默认 20**，理由见 20.2 问题 4），允许范围 1-20；
- 支持配置单次 Agent 运行总超时；
- 支持为 Agent 启用或禁用具体工具；
- 启动时为默认 Agent 写入可直接运行的默认配置。

V2 可以先通过数据库默认值和配置文件维护上述配置，不要求交付完整管理页面。

#### 模型 Tool Calling

- OpenAI-compatible 请求携带标准 `tools` 和 `tool_choice` 字段；
- 支持流式响应中的 `tool_calls` 增量拼接；
- 正确合并同一 tool call 的 `id`、函数名和 JSON arguments；
- 支持一次模型响应包含多个 tool calls，并按稳定顺序执行；
- tool result 以标准 `role=tool` 消息回传模型；
- 兼容当前默认模型 `kimi-k2.5` 使用的 OpenAI-compatible Tool Calling 协议；
- 模型未请求工具时直接完成普通文本回答。

#### Agent 执行循环

- 显式运行状态机；
- 每个会话同一时刻只允许一个活跃运行；
- 不同会话可以并行运行；
- 最大工具迭代次数控制；
- 单次模型调用超时、单次工具超时和整次运行超时；
- 工具失败后将结构化错误作为 observation 返回模型，由模型决定重试、换工具或结束；
- 达到最大迭代次数时停止执行工具，进行一次不携带 tools 的最终总结，并记录迭代上限 metadata；
- 浏览器断开只结束事件转发订阅，不取消 Agent 后台运行。

#### 工具框架

- `ToolDescriptor`：名称、描述、JSON Schema、风险级别和结果限制；
- `ToolRegistry`：按 Agent 配置解析当前可用工具；
- `ToolInvoker`：统一调用入口；
- `ToolExecutionContext`：runId、agentId、sessionId、workspace 和截止时间；
- 参数 JSON Schema 校验；
- 工具名称白名单；
- 统一超时、异常映射和结果截断；
- 工具调用及结果持久化；
- 日志中清理敏感参数和超长结果。

#### 首批内置工具

| 工具 | V2 行为 | 默认状态 | 风险级别 |
|---|---|---:|---|
| `get_current_time` | 按指定时区返回当前时间 | 启用 | 只读、低风险 |
| `calculator` | 执行受限数学表达式计算，不使用脚本引擎 | 启用 | 只读、低风险 |
| `list_dir` | 列出 Agent workspace 内的文件和目录 | 启用 | 只读、中风险 |
| `read_file` | 读取 workspace 内 UTF-8 文本文件，限制大小 | 启用 | 只读、中风险 |
| `write_file` | 在 workspace 内创建或覆盖文本文件 | 禁用 | 写入、高风险 |
| `apply_patch` | 对 workspace 内文本文件应用受限补丁 | 禁用 | 写入、高风险 |
| `web_fetch` | 获取经过安全校验的 HTTP/HTTPS 文本资源 | 禁用 | 网络、高风险 |

安全约束：

- 文件路径必须经过规范化并验证最终路径仍位于当前 Agent workspace；
- 禁止绝对路径、`..` 穿越、符号链接逃逸和设备文件；
- `read_file` 默认单文件上限 1 MiB，工具结果默认上限 64 KiB；
- `write_file` 和 `apply_patch` 必须通过 Agent 工具配置显式启用；
- `web_fetch` 默认关闭，启用后仍必须阻止 localhost、内网、链路本地地址、云元数据地址和非 HTTP 协议；
- V2 不提供宿主机 `exec`。命令执行必须等 Docker Sandbox 版本完成后再注册。

#### 持久化与重放

- Agent run 状态持久化；
- 每次工具执行的请求、结果、耗时和状态持久化；
- 工具调用消息和工具结果消息进入会话上下文；
- `tool_call`、`tool_result`、`content`、`error` 和 `done` 最终事件可重放；
- `content_delta` 等高频增量只做实时广播，不逐条入库；
- 最终事件必须先持久化，再发布到事件中枢。

#### 前端兼容

- 继续使用当前聊天页面、会话列表和历史记录；
- 复用前端现有工具消息组件和 SSE 消费逻辑；
- 如果现有字段与 Java 事件不一致，以兼容前端现有协议为优先；
- 工具执行中展示名称、状态和可折叠结果；
- 工具失败不得导致整个页面崩溃；
- 切换会话后重新进入，历史中的工具调用顺序与最终回答保持一致。

### 3.2 明确不交付

以下能力不进入 V2，防止版本范围失控：

- 用户注册、登录、角色和权限校验；
- Provider、Agent、用户等完整后台管理系统；
- MCP Server 接入和远程工具发现；
- Skill 安装、Skill 市场和脚本插件；
- 宿主机 Shell、Docker Sandbox 和代码运行环境；
- 长期记忆、向量知识库和完整 RAG；
- Cron、定时任务和后台任务管理页面；
- Telegram、Discord、Slack、飞书等外部渠道；
- 多 Agent、Subagent、Team Chat；
- Redis、多实例调度和分布式事件总线；
- 图片生成、语音和多模态工具。

## 4. 核心用例

### 4.1 文件阅读任务

用户输入：

```text
读取项目 README，并总结这个项目使用了哪些技术。
```

预期过程：

1. 模型选择 `list_dir` 或直接选择 `read_file`；
2. 工具读取 workspace 内的 `README.md`；
3. 后端推送并持久化 `tool_call` 和 `tool_result`；
4. 工具结果加入模型上下文；
5. 模型根据真实文件内容生成最终总结；
6. 切换到其他会话再返回，仍能看到工具过程和最终回答。

### 4.2 多工具任务

用户输入：

```text
读取 README 中的 Java 版本，把 17 乘以 8，并告诉我当前上海时间。
```

Agent 应能够在一次运行内调用 `read_file`、`calculator` 和 `get_current_time`，再综合结果回答。

### 4.3 工具失败恢复

用户要求读取不存在的文件时：

1. `read_file` 返回结构化 `FILE_NOT_FOUND`；
2. 错误结果作为 tool result 回传模型；
3. 模型可以选择 `list_dir` 查找正确文件，或向用户解释问题；
4. 不允许因为一个工具异常而丢失整个会话运行记录。

## 5. 总体架构

```text
ChatController
  -> ChatApplicationService
  -> AgentRunCoordinator
       -> AgentKernel                      (agent-core)
            -> ConversationContextBuilder
            -> LLMService                  (infra-ai port)
            -> ToolRegistry/ToolInvoker    (agent-core port)
                 -> BuiltinToolAdapters    (runtime-integration)
       -> AgentRunRepository               (bootstrap)
       -> ChatEventPublisher
            -> database first
            -> ChatEventHub
                 -> POST stream subscriber
                 -> GET reconnect subscriber
```

### 5.1 模块职责

| 模块 | V2 职责 |
|---|---|
| `framework` | 强类型事件、统一错误码、通用结果和安全日志辅助类 |
| `infra-ai` | OpenAI-compatible 请求/响应模型、流式解析、Tool Calling 聚合 |
| `agent-core` | Agent 状态机、运行命令、上下文、工具端口和循环策略 |
| `runtime-integration` | 内置工具、workspace 文件系统适配器、HTTP 安全访问适配器 |
| `bootstrap` | Controller、应用服务、数据库 Repository、Bean 装配和迁移 |
| `frontend` | 复用聊天页面并展示工具调用事件 |

依赖约束：

- `agent-core` 不依赖 Spring MVC、JdbcTemplate、具体模型 SDK 或具体工具实现；
- `infra-ai` 不访问会话数据库；
- `runtime-integration` 实现 `agent-core` 的工具端口；
- `bootstrap` 负责把端口与实现装配起来；
- Controller 不包含 Agent loop，也不直接执行工具。

## 6. Agent 状态机

```text
CREATED
  -> CONTEXT_LOADING
  -> MODEL_RUNNING
       -> MODEL_TEXT_COMPLETED -> COMPLETED
       -> TOOL_REQUESTED
            -> TOOL_VALIDATING
            -> TOOL_RUNNING
            -> TOOL_SUCCEEDED / TOOL_FAILED
            -> MODEL_RUNNING
  -> LIMIT_REACHED
       -> FINAL_DELIVERY
       -> COMPLETED / FAILED
  -> TIMED_OUT
  -> FAILED
```

### 6.1 状态规则

1. 每次用户消息创建一个唯一 `runId`。
2. `runId` 与 `agentId + sessionId` 关联。
3. 只有终态可以结束活跃运行标记。
4. 工具失败不是默认终态；失败 observation 返回模型后仍可继续循环。
5. 数据库或协议解析失败属于运行失败，必须产生 `error` 和 `done`。
6. 达到工具迭代上限进入 LIMIT_REACHED，不再执行工具，但必须参考 FastClaw 追加系统提示并进行一次不携带 tools 的最终总结调用。
7. SSE 订阅断开不改变 Agent 状态。
8. 每个 assistant tool call 必须产生相同 ID 的 tool result；执行器漏返回时由 AgentKernel 合成失败结果。
9. 连续 3 次相同工具和 arguments 触发循环保护；连续 3 轮工具全部失败时禁用工具并尽力回答。

### 6.2 迭代计数

- 一次包含一个或多个 tool calls 的模型响应计为一次工具迭代；
- 同一响应中的多个工具调用按顺序执行，V2 暂不并行；这是相对 FastClaw 可配置并行调用的有意收缩，用于避免写工具竞争同一 workspace；
- 工具参数校验失败也计入本轮迭代；
- 默认最大 8 轮，最大可配置为 20 轮。

## 7. 领域接口设计

### 7.1 Agent Kernel

```java
public interface AgentKernel {
    AgentRunResult run(AgentRunCommand command, AgentEventSink eventSink);
}

public record AgentRunCommand(
        String runId,
        String userId,
        String agentId,
        String sessionId,
        String userMessage,
        AgentRuntimeConfig config) {
}
```

`AgentKernel` 同步表达一条后台执行线程中的完整 Agent 运行；异步调度由 `AgentRunCoordinator` 负责，不把线程模型泄漏进核心领域。

### 7.2 模型端口

```java
public interface LLMService {
    ModelResponse stream(ModelRequest request, ModelEventListener listener);
}

public sealed interface ModelResponse {
    record Text(String content, TokenUsage usage) implements ModelResponse {}
    record ToolCalls(List<ToolCall> calls, TokenUsage usage) implements ModelResponse {}
}
```

### 7.3 工具端口

```java
public interface AgentTool {
    ToolDescriptor descriptor();
    ToolResult execute(ToolArguments arguments, ToolExecutionContext context);
}

public interface ToolRegistry {
    List<ToolDescriptor> availableTools(String agentId);
    AgentTool requireEnabled(String agentId, String toolName);
}
```

### 7.4 工具结果

工具不得向上抛出实现相关异常。统一映射为：

```java
public record ToolResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        boolean truncated,
        long durationMs) {
}
```

建议错误码：

- `TOOL_NOT_FOUND`
- `TOOL_NOT_ENABLED`
- `TOOL_ARGUMENT_INVALID`
- `TOOL_TIMEOUT`
- `TOOL_EXECUTION_FAILED`
- `WORKSPACE_PATH_FORBIDDEN`
- `FILE_NOT_FOUND`
- `FILE_TOO_LARGE`
- `NETWORK_TARGET_FORBIDDEN`
- `RESULT_TOO_LARGE`

## 8. SSE 事件协议

V2 保留现有外层结构：

```json
{
  "seq": 12,
  "type": "tool_call",
  "data": {}
}
```

### 8.1 事件类型

| type | 是否持久化 | data 主要字段 |
|---|---:|---|
| `run_started` | 是 | `runId`（**OpenAgent 自增事件，非 FastClaw 基线**，见 20.2 问题 3） |
| `content_delta` | 否 | `delta` |
| tool_call | 是 | id、name、arguments；字段名与 FastClaw 前端完全一致 |
| tool_result | 是 | id、name、result、metadata；字段名与 FastClaw 前端完全一致 |
| content | 是 | content、可选 metadata |
| error | 是 | message，可增补 code |
| done | 是 | 可选 runId、status；旧前端忽略未知字段 |

### 8.2 发布顺序

1. 可恢复事件先写入数据库并获得 `seq`；
2. 再发布到 `ChatEventHub`；
3. 在线订阅者实时消费；
4. 重连先订阅 Hub，再读取 `since` 之后的数据库事件；
5. 通过 `seq` 去重订阅与回放交界处的重复事件。

工具参数中可能包含敏感信息，返回前必须依据 descriptor 的敏感字段配置进行遮盖。前端展示参数不代表日志可以原样记录参数。
协议兼容规则：

- toolCallId 用于数据库和历史消息，实时事件必须使用现有前端消费的 data.id；
- 工具输出实时事件必须放在 data.result，不得自创 data.content 替代；
- arguments 保持 JSON 字符串，不在事件层改成对象；
- runId、错误码等只能作为向后兼容的附加字段；
- 实施前再次核对 FastClaw ChatStreamEvent 和 chat-screen.tsx 的事件 switch。

## 9. 数据库设计

V2 使用 Flyway 增量迁移，建议新增以下表。

### 9.1 `agent_runs`

| 字段 | 说明 |
|---|---|
| `id` | runId，主键 |
| `user_id` | 当前固定为 `local-user`，为后续认证预留 |
| `agent_id` | Agent ID |
| `session_id` | 会话 ID |
| `status` | CREATED/RUNNING/COMPLETED/FAILED/TIMED_OUT/LIMIT_REACHED |
| `tool_iterations` | 已执行工具迭代数 |
| `error_code` | 终态错误码，可空 |
| `error_message` | 清理后的错误信息，可空 |
| `started_at` | 开始时间 |
| `completed_at` | 完成时间，可空 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

索引：

- `(user_id, agent_id, session_id, created_at)`；
- `(status, updated_at)`。

### 9.2 `tool_executions`

| 字段 | 说明 |
|---|---|
| `id` | 内部主键 |
| `run_id` | 所属 Agent run |
| `tool_call_id` | 模型返回的 Tool Call ID |
| `sequence` | 同一 run 内执行顺序 |
| `tool_name` | 工具名称 |
| `arguments_json` | 清理后的参数 JSON |
| `status` | REQUESTED/RUNNING/SUCCEEDED/FAILED/TIMED_OUT |
| `result_content` | 截断后的工具结果 |
| `error_code` | 工具错误码，可空 |
| `error_message` | 清理后的错误信息，可空 |
| `duration_ms` | 执行耗时 |
| `created_at` | 创建时间 |
| `completed_at` | 完成时间，可空 |

约束和索引：

- `UNIQUE(run_id, tool_call_id)`；
- `(run_id, sequence)`。

### 9.3 `agent_tools`

| 字段 | 说明 |
|---|---|
| `agent_id` | Agent ID |
| `tool_name` | 工具名称 |
| `enabled` | 是否启用 |
| `config_json` | 工具非敏感配置 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

主键使用 `(agent_id, tool_name)`。敏感凭证不放入 `config_json`，V2 如需外部凭证只允许通过环境变量注入。

### 9.4 会话消息扩展

若现有 `session_messages` 无法表达工具消息，增量增加：

- `tool_call_id`；
- `tool_name`；
- `metadata_json`。

`role` 允许 `user`、`assistant`、`tool`。不修改已有主键和 `seq` 语义。

## 10. API 设计

现有 Chat API 路径保持不变：

- `POST /api/chat/stream`
- `GET /api/chat/subscribe`
- `GET /api/chat/history`
- `GET /api/chat/sessions`

V2 新增最小工具配置 API（**注意 `/api/tools` 路径冲突，见 20.2 问题 2**）：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/tools` | ~~返回内置工具及默认启用状态~~ **V2 暂不实现或必须对齐 fastclaw ToolsConfig 形状（见 20.2 问题 2）** |
| GET | `/api/agents/{agentId}/tools` | 返回 Agent 的有效工具配置 |
| PUT | `/api/agents/{agentId}/tools/{toolName}` | 启用、禁用或更新非敏感工具配置 |
| GET | `/api/agent-runs/{runId}` | 查询运行状态和工具执行摘要 |

虽然 V2 暂不校验权限，但 API 必须保留 `userId/agentId` 数据边界，不能为了本地模式把关联字段删除。

## 11. 配置项

建议增加以下配置：

```yaml
openagent:
  agent:
    max-tool-iterations: ${OPENAGENT_AGENT_MAX_TOOL_ITERATIONS:8}
    run-timeout: ${OPENAGENT_AGENT_RUN_TIMEOUT:10m}
  tools:
    execution-timeout: ${OPENAGENT_TOOL_TIMEOUT:30s}
    max-result-chars: ${OPENAGENT_TOOL_MAX_RESULT_CHARS:65536}
    workspace-root: ${OPENAGENT_WORKSPACE_ROOT:./workspace}
    read-file-max-bytes: ${OPENAGENT_READ_FILE_MAX_BYTES:1048576}
    web-fetch-enabled: ${OPENAGENT_WEB_FETCH_ENABLED:false}
    web-fetch-max-bytes: ${OPENAGENT_WEB_FETCH_MAX_BYTES:1048576}
```

所有配置使用 `@ConfigurationProperties`，不在业务类中写 `@Value` 或硬编码超时。

## 12. 安全设计

### 12.1 Workspace 隔离

- 每个会话使用独立目录：{workspaceRoot}/{agentId}/sessions/{sessionId}，与 FastClaw 的 agent/session 作用域保持一致；
- V2 暂无 project runtime；后续再兼容 {agentId}/projects/{projectId}/{sessionId}；
- 路径解析使用 `Path.toRealPath()` 或等价的安全校验；
- 新文件写入时校验最近已存在父目录的真实路径；
- 不跟随可能逃出 workspace 的符号链接；
- 文件名、工具参数和错误信息不得泄露宿主机无关绝对路径。

### 12.2 网络访问

`web_fetch` 启用时必须：

- 仅允许 `http` 和 `https`；
- DNS 解析后校验每个目标地址；
- 拒绝 loopback、private、link-local、multicast 和云元数据网段；
- 每次重定向后重新校验目标；
- 限制重定向次数、响应大小和总耗时；
- 不自动携带系统代理凭证、Cookie 或 Authorization；
- 响应只作为不可信文本交给模型。

### 12.3 资源控制

- Agent run、模型调用和工具调用分别设置超时；
- 工具线程池与模型线程池隔离；
- 工具队列满时快速失败，不占满 Web 请求线程；
- 限制工具结果长度和模型上下文总长度；
- 超时后取消 Future，并将状态持久化为 `TIMED_OUT`。

### 12.4 敏感数据

- API key 继续只从环境变量加载；
- 数据库、SSE、日志中不保存 API key；
- 工具 descriptor 可以声明敏感参数字段；
- 日志只记录参数摘要、结果长度、耗时、状态和关联 ID。

## 13. 异常与恢复策略

| 场景 | 处理方式 |
|---|---|
| 模型返回非法 tool arguments JSON | 记录 `TOOL_ARGUMENT_INVALID` 并将错误 observation 返回模型 |
| 模型请求未注册工具 | 返回 `TOOL_NOT_FOUND`，不反射调用任意 Bean |
| 工具未为 Agent 启用 | 返回 `TOOL_NOT_ENABLED` |
| 工具超时 | 取消工具任务，持久化 `TIMED_OUT`，允许模型继续处理 |
| 模型调用失败 | 运行进入 FAILED，写入 `error` 和 `done` |
| 浏览器断开 | 关闭订阅，不改变运行状态 |
| 服务进程退出 | V2 不自动续跑；启动时将遗留 RUNNING 标记为 FAILED/INTERRUPTED，并为孤立 tool call 补 interrupted tool result |
| 数据库写入失败 | 不发布伪成功事件，运行失败并记录服务日志 |
| 达到最大迭代次数 | 禁用工具后调用模型生成最终交付；总结失败才返回固定兜底文本 |
| 连续 3 次相同工具和参数 | 停止重复调用，注入循环提示并进入最终交付 |
| 连续 3 轮工具全部失败 | 下一次模型调用不携带 tools，输出标明未验证的尽力回答 |
| 执行器漏返回 tool result | 按 tool call ID 合成失败结果，保证模型消息协议闭合 |

V2 的恢复目标是“断开页面可继续”，不承诺“Java 进程重启后从中间步骤继续执行”。进程级断点续跑放入后续版本。

## 14. 可观测性

日志统一携带：

- `requestId`
- `runId`
- `agentId`
- `sessionId`
- `toolCallId`

建议指标：

- `openagent.agent.runs`：按终态计数；
- `openagent.agent.run.duration`：运行耗时；
- `openagent.agent.tool.iterations`：每次运行工具轮数；
- `openagent.tool.executions`：按工具名和状态计数；
- `openagent.tool.duration`：工具耗时；
- `openagent.tool.result.truncated`：结果截断次数；
- `openagent.model.calls`：模型调用次数；
- `openagent.chat.active.runs`：当前活跃运行数。

日志级别：

- run 开始、完成和失败使用 `INFO/WARN/ERROR`；
- 工具开始和完成使用 `INFO`，不打印完整参数及结果；
- SSE 客户端正常断开使用 `DEBUG`；
- 数据库写入失败使用 `ERROR`。

## 15. 实施里程碑

### M1：领域模型与数据库（1-2 天）✅ 已完成（2026-07-16）

> 前置：先完成 PROJECT_REFACTORING_PLAN 的 Phase 3（OpenAgentStore 按聚合拆 Repository、
> 种子数据剥离、seq 竞态修正），M1 的新 Repository 直接按拆分后的结构落地（见 20.1）。
> ✅ Phase 3 已完成（commit dbb97af）。

> **M1 完成记录**：
> - **交付物**：`V2__agent_runs_and_tools.sql`（agent_runs / tool_executions /
>   agent_tools 三表 + session_messages 增列 tool_call_id/tool_name/metadata_json）；
>   `AgentRunStatus`/`ToolExecutionStatus` 枚举（agentrun 域）；
>   `AgentRunRecord`/`ToolExecutionRecord`/`AgentToolRecord` 记录 +
>   `AgentRunRepository`/`ToolExecutionRepository`/`AgentToolRepository` 仓储（persistence）；
>   `ToolCatalog` 内置工具目录（tool 域，7 工具 4 默认启用）；
>   `AgentProperties`（maxToolIterations 默认 8、@Min(1)@Max(20) 启动期校验）与
>   `ToolProperties`（tool 域）；application.yml 增补 `openagent.agent.*`/`openagent.tools.*`；
>   DataSeeder 扩展：agent_tools 补种（不覆盖用户显式启停）+ 启动时遗留 RUNNING 标记 INTERRUPTED
> - **参考文件**：FastClaw `internal/store/database.go`（tool_executions 的 sequence 沿用
>   其 INSERT 内 COALESCE(MAX)+1 原子分配模式）、`internal/config/config.go`
>   （MaxToolIterations 默认值对照）；ragent 仓储分层与 Properties 风格
> - **有意差异**：见 20.2 问题 3（run_started 自增事件，本里程碑只建表未发事件）、
>   问题 4（迭代默认 8）；决策落地见 20.4（方案 B、/api/tools 不实现——本里程碑未新增任何 API）
> - **测试**：AgentRunRepositoryTest（run 生命周期、sequence 原子分配、
>   (run_id, tool_call_id) 唯一约束、启动恢复只触达活跃 run）、ToolSeedTest（种子
>   默认启用集 + 重种不覆盖用户选择）、AgentPropertiesTest（边界 1/20 通过、0/21 启动失败）；
>   全量 mvnw verify 24 tests 全绿；迁移在既有库（V1 已就位）与空库均验证通过

- 先阅读 FastClaw session/message/event 持久化实现，以及 ragent Service/DAO 分层样例；

- 增加 Agent run、Tool Call、Tool Result 强类型模型；
- 增加 Flyway V2 migration；
- 实现 AgentRunRepository、ToolExecutionRepository；
- 增加默认 Agent 工具配置种子数据；
- 增加配置属性类和边界校验。

退出条件：迁移可从空库和现有库执行；运行与工具记录可以独立增删查改；所有 Repository 测试通过。

### M2：模型 Tool Calling（2-3 天）

> 本里程碑吸收 PROJECT_REFACTORING_PLAN Phase 4：现有 bootstrap 内的
> OpenAiCompatibleChatModelGateway 迁移/重写到 infra-ai，接口按第 7 章定义
> 替换既有占位骨架（见 20.2 问题 5），同时解决两模块 ToolCall 同名类冲突。

- 先阅读 FastClaw internal/provider/openai.go、provider 测试和 tool recovery 测试，并保存真实 Kimi 流式响应 fixture；

- `infra-ai` 实现 OpenAI-compatible tools 请求；
- 实现流式 tool call arguments 拼接；
- 建立文本完成与工具请求两类模型结果；
- 使用录制 fixture 覆盖分片边界、多个工具和非法 JSON；
- 对当前 `kimi-k2.5` 做真实 smoke test。

退出条件：给定固定模型流，能够稳定还原完整 ToolCall 列表；普通文本聊天不回归。

### M3：Agent Kernel 与工具框架（2-3 天）

- 先逐段对照 FastClaw internal/agent/loop.go 和 tools/registry.go，列出循环、失败轮次、消息配对和最终交付行为；

- 实现 Agent 状态机和多轮循环；
- 实现 ToolRegistry、统一 Invoker 和策略包装；
- 接入迭代上限、超时和结果截断；
- 将 AgentKernel 接入现有 ChatTurnCoordinator；
- 完成断线继续执行和同会话冲突控制。

退出条件：Fake Model 可以驱动“工具请求 -> 工具结果 -> 最终文本”完整闭环。

### M4：内置工具与安全边界（2-3 天）

- 先阅读 FastClaw 文件工具、workspace、web fetch 及其测试；Java 实现按 ragent Service/配置/异常风格落地；

- 实现时间和计算器工具；
- 实现 workspace、目录列表和文件读取；
- 实现默认关闭的文件写入和补丁工具；
- 实现默认关闭的安全 `web_fetch`；
- 覆盖路径穿越、符号链接逃逸、SSRF、超时和大结果测试。

退出条件：所有工具通过正常、边界和攻击输入测试；不存在宿主机 Shell 执行入口。

### M5：事件、前端联调和验收（2 天）

- 先核对 FastClaw events.go、event_hub.go、ChatStreamEvent 和 chat-screen.tsx，禁止凭文档猜测事件字段；

- 增加 V2 SSE 事件并保持旧事件兼容；
- 前端展示工具调用和结果；
- 回放历史工具事件；
- 完成真实模型端到端用例；
- 更新 README、配置说明和演示步骤。

退出条件：三个核心用例均可在现有 Chatbot 页面完成，切换会话不丢失结果。

预计总工作量：单人约 9-13 个有效开发日。若暂不实现 `web_fetch` 和写文件工具，可压缩到 7-9 日，但模型 Tool Calling、Agent loop、持久化和安全文件读取不可删减。

## 16. 测试方案

### 16.1 单元测试

- Tool Call 流式增量合并；
- tool arguments JSON Schema 校验；
- Agent 状态转换；
- 最大迭代次数及无工具最终总结；
- 相同工具和参数连续调用 3 次的循环保护；
- 连续 3 轮工具全部失败后的工具禁用；
- 每个 tool call 与 tool result 严格配对，包含执行器漏结果和中断恢复；
- 工具超时与异常映射；
- 结果截断；
- calculator 非法表达式；
- workspace 路径规范化；
- SSRF 地址分类和重定向复验；
- ChatEventHub 背压和终态事件保留。

### 16.2 集成测试

- 普通无工具聊天保持可用；
- 单工具完整闭环；
- 多工具顺序调用；
- assistant 同时返回正文和 tool calls 时的消息及事件顺序；
- 工具失败后模型恢复；
- 客户端断开后工具和最终回答仍入库；
- 重连回放不重复 tool event；
- 同一 session 并发请求返回 409；
- 不同 session 可以并行；
- 运行超时后状态和事件一致；
- 现有数据库升级后历史聊天可读。

### 16.3 前端端到端测试

1. 新建会话并发送文件阅读任务；
2. 等待出现工具调用状态；
3. 切换到历史会话；
4. 等待后台执行完成；
5. 切回新会话；
6. 断言工具调用、工具结果和最终回答顺序正确；
7. 刷新页面，断言历史仍可完整显示。

### 16.4 发布门禁

- `mvnw -pl bootstrap -am test` 全部通过；
- 前端构建通过；
- 核心工具安全测试全部通过；
- 真实 `kimi-k2.5` smoke test 通过；
- 日志扫描确认无 API key、Authorization 和完整敏感参数；
- 从当前数据库备份升级演练成功；
- 普通聊天与断线续跑回归通过。
- V2 PR/实施记录列出实际阅读的 FastClaw/ragent 文件及所有有意差异。

## 17. 验收标准

V2 必须同时满足以下条件：

1. 模型能够通过标准 Tool Calling 自主选择至少一个内置工具。
2. Agent 能完成至少三轮 `model -> tool -> model` 而不丢失上下文。
3. 单次响应包含多个 tool calls 时，执行顺序和 tool call ID 正确。
4. 工具调用、工具结果和最终回答均可持久化和重放。
5. 切换会话或关闭浏览器不会取消后台运行。
6. 回到会话后能够看到完整工具过程和最终答案，不重复展示持久化事件。
7. 文件工具无法访问 workspace 外部路径。
8. `web_fetch` 无法访问本机、内网和云元数据地址。
9. 未显式启用的写入和网络工具不可调用。
10. 达到超时或迭代上限时运行能确定结束，不产生无限循环。
11. 当前普通聊天流程、会话历史和默认模型配置不回归。
12. 全部自动化测试和发布门禁通过。
13. 关键行为已与 FastClaw 做差异验证，Java 代码结构通过 ragent 风格评审；所有偏离项均有说明和测试。

## 18. 演示脚本

面试或项目展示建议使用以下流程：

1. 打开默认 Agent Chatbot 页面；
2. 输入“读取 README，并总结项目技术栈”；
3. 展示模型自主调用 `read_file`；
4. 展开查看工具参数和结果摘要；
5. 在执行中切换到另一个历史会话；
6. 再切回原会话，展示后台任务已完成；
7. 刷新页面，证明工具过程和最终回答已持久化；
8. 尝试读取 workspace 外文件，展示安全拒绝；
9. 展示 `agent_runs` 和 `tool_executions` 记录，说明运行可追踪。

该脚本同时体现 OpenAgent 的核心价值：模型接入、Agent 编排、工具执行、安全边界、事件流、持久化和断线恢复，而不依赖尚未完成的管理后台。

## 19. 后续版本预留

V2 完成后建议按以下顺序演进：

- V2.1：MCP、Skill 和 Docker Sandbox；
- V2.2：长期记忆、知识库和上下文压缩；
- V3：认证与管理后台、定时任务、外部渠道；
- V4：多 Agent、分布式运行、Redis/S3 和云部署能力。

在 V2 完成前不提前引入上述能力，优先保证单机 Agent 工具闭环稳定、清晰且可演示。

## 20. 评审发现的问题与重构衔接（必读）

> 本章为 2026-07-16 对照 FastClaw / ragent 源码及当前代码库的核查结论。
> 实施 V2 前必须先处理本章内容；各里程碑执行时以本章修正为准，与正文冲突处以本章为准。

### 20.1 规范化重构的完成情况

`PROJECT_REFACTORING_PLAN.md` 的六个 Phase 中，V2 开工时的状态如下：

| Phase | 内容 | 状态 |
|---|---|---|
| Phase 0 | 修编译 / 仓库卫生 / Lombok / 删空壳模块（cli、runtime-integration） | ✅ 已完成（commit 52fa3a7） |
| Phase 1 | framework 打底：Result / 错误码 / 三层异常 / GlobalExceptionHandler / SseStreamWriter | ✅ 已完成（commit c119a84） |
| Phase 2 | 业务域分包 + Controller/Service 规范化 + SSE 对齐 fastclaw | ✅ 已完成（commit b528ad7） |
| Phase 3 | **持久层拆分**：OpenAgentStore 按聚合拆 Repository、种子数据剥离 DataSeeder、修正 synchronized+@Transactional 的 seq 竞态 | ✅ 已完成（commit dbb97af），M1 已按新结构落地 |
| Phase 4 | 网关落位 infra-ai + 事件模型强类型化 | ❌ 未做，**不再单独实施**：与 V2 M2（infra-ai Tool Calling 流式解析）高度重合，单独做会把网关重写两遍，并入 M1/M2 一起交付 |
| Phase 5/6 | 前端统一请求层 / 契约对账 / chat-screen 拆分 / 测试补齐 | ❌ 未做，与 V2 无依赖关系，可并行或延后 |

**结论：V2 的实施顺序为 Phase 3 → M1（吸收 Phase 4 的 Repository 部分）→ M2（吸收 Phase 4 的网关部分）→ M3 → M4 → M5。**

Phase 2 已落地、V2 必须遵守的既成约定（记录于项目记忆与 commit b528ad7）：

- **wire 协议保持 fastclaw 裸 JSON**：不用 `Result<T>` 包装，错误为 HTTP 状态码 + `{"error": msg}`，由 `GlobalExceptionHandler` 从三层异常映射（NOT_FOUND→404、CONFLICT→409、UNAVAILABLE→503、Client→400、Remote→502）；
- 业务域垂直分包已就位：`agent/`、`chat/`、`identity/`、`status/`、`web/`，V2 新增代码沿用该结构；
- SSE 已对齐 fastclaw：`id: <seq>` 行、30s `: ping` 心跳、subscribe 初始 `: ok`、Last-Event-ID 优先于 ?since、丢弃 seq<=cursor 与 content_delta；
- 三层异常 + 错误码、`@ConfigurationProperties`（ChatProperties/PlatformProperties）、`@RequiredArgsConstructor` + `@Slf4j` 中文日志已是现行规范。

### 20.2 评审发现的事实性问题（实施时按此修正）

#### 问题 1【与现状冲突】`runtime-integration` 模块已被删除

正文 2.2 节、第 5 章架构图、5.1 模块职责表引用的 `runtime-integration` 模块已在 Phase 0 作为空壳删除（连同 `cli`）。两个方案待决策：

- **方案 A**：V2 M4 时恢复 `runtime-integration` 模块，按正文的端口/适配器分层承载内置工具与 workspace 适配器；
- **方案 B**：内置工具适配器放入 bootstrap 的 `tool/` 业务域（`tool/adapter/` 实现 agent-core 端口），少维护一个模块，V2 范围内更简单，后续工具规模扩大再抽模块。

未决策前正文第 5 章的架构图按方案 A 阅读；**开工 M4 前必须定案**。

#### 问题 2【协议冲突】`GET /api/tools` 与前端现有消费不兼容

正文第 10 章将 `GET /api/tools` 定义为"返回内置工具及默认启用状态"，但前端 `api.ts` 的 `getTools()`（约 1606 行）期望 fastclaw 的 `ToolsConfig { categories, toolProviders, tools }` 结构，且存在 `PUT /api/tools`（保存整份配置）与 `GET /api/agents/{id}/tools/registered`（约 1336 行）。若同一路径返回自定义形状，前端工具设置页会损坏。修正：

- `GET/PUT /api/tools` 若在 V2 实现，**必须对齐 fastclaw 的 `ToolsConfig` 形状**；V2 若不做工具设置页，则不实现该路径（前端页面报 404 属于已知的前后端契约缺口，见 PROJECT_REFACTORING_PLAN Phase 5.2）；
- V2 最小工具配置 API 保留 `GET /api/agents/{agentId}/tools`、`PUT /api/agents/{agentId}/tools/{toolName}`、`GET /api/agent-runs/{runId}`（前端现有代码不消费这些路径，无冲突）；
- 若实现 `GET /api/agents/{id}/tools/registered`，返回形状以前端 `AgentRegisteredTool` 接口定义为准。

#### 问题 3【自创事件】`run_started` 不是 FastClaw 基线事件

FastClaw 实际事件类型为 `content_delta / content / tool_call / tool_result / error / done / steer / turn_pending / subagent_progress`，**不存在 `run_started`**（已 grep `loop.go`、`events.go` 确认）。前端事件 switch 无 default 分支，未知类型被静默忽略，因此不会破坏前端。按 1.1 节"有意偏离必须记录"规则在此声明：

- `run_started` 为 OpenAgent 自增事件（用于 run 可观测与 `agent_runs` 关联），非 FastClaw 兼容行为；
- 风险：无（前端忽略未知类型）；测试要求：验证旧前端在收到 `run_started` 时无渲染异常。

#### 问题 4【默认值偏离】`maxToolIterations` 默认 8 vs FastClaw 默认 20

FastClaw `internal/config/config.go:717` 的默认值为 20。正文 3.1 的"默认 8"是**有意收缩**，原因：本地单机模式 + 首批以只读工具为主，8 轮足够覆盖核心用例且减少失控消耗；允许配置到 20 与 FastClaw 上限对齐。此偏离已按 1.1 节规则在此记录。

#### 问题 5【接口签名不一致】第 7 章接口与 agent-core / infra-ai 现有骨架不同

当前代码库中的既有骨架（零消费的占位代码）与正文第 7 章定义不一致：

| 位置 | 现有骨架 | 正文定义 |
|---|---|---|
| `agent-core` AgentKernel | `AgentRunHandle run(command, eventSink)` | `AgentRunResult run(command, eventSink)` |
| `agent-core` AgentRunCommand | `(runId, RequestIdentity identity, agentId, sessionKey, input, Map effectiveConfig)` | `(runId, userId, agentId, sessionId, userMessage, AgentRuntimeConfig config)` |
| `infra-ai` LLMService | `ModelCallHandle streamChat(request, listener)` | `ModelResponse stream(request, listener)`（sealed ModelResponse） |

**修正：现有 agent-core / infra-ai 接口为占位骨架，M1/M2 实施时按正文第 7 章定义直接替换，不做兼容**。同时解决两模块的 `ToolCall` 同名类冲突（`ai.openagent.agent.tool.ToolCall` vs `ai.openagent.infra.ai.model.ToolCall`，保留 infra-ai 一份，agent-core 引用之或按第 7 章重新定义）。

#### 问题 6【正文过时表述】1.3 节"统一返回"与 Phase 2 决策冲突

1.3 节表格"统一返回：普通 JSON API 使用统一泛型返回"已被 Phase 2 的 wire 协议决策否决（见 20.1）。以 20.1 为准：**REST 接口直接返回 VO（fastclaw 形状），`Result`/`Results` 保留在 framework 但不上 wire**；ragent 该行仅参考其"Controller 不拼装异常响应"的精神。

### 20.3 核查中确认无误的关键基线（可放心依赖）

以下正文断言已对照源码逐条验证属实，实施时可直接引用：

- 循环保护：连续 3 次相同工具 + 相同 arguments 触发（`loop.go:2194` `consecutiveCount >= 3`）；
- 连续 3 轮工具全部失败后禁用工具尽力回答（`loop.go:2075-2082`）；
- 迭代上限后不携带 tools 做最终总结，总结失败才用固定兜底文本（`loop.go:2392-2411`）；
- 前端 `tool_call` 消费 `data.id / data.name / data.arguments`（arguments 为 JSON 字符串）、`tool_result` 消费 `data.id / data.result / data.metadata`（`chat-screen.tsx:1554-1623`）；前端还依赖 `write_file` 结果文本形如 `Written N bytes` 来刷新文件面板（1607 行）——**write_file 工具的结果文本格式必须与 FastClaw 逐字一致**；
- 事件先持久化后发布、订阅先于回放、按 seq 去重（`handlers.go handleChatSubscribe`）；
- 1.2 节引用的全部 FastClaw 文件与 1.3 节引用的全部 ragent 文件均存在（注：`apply_patch.go` 在 `internal/agent/tools/` 目录下）。

### 20.4 待用户决策清单

| # | 决策项 | 选项 | 影响里程碑 | 决策结果 |
|---|---|---|---|---|
| 1 | 内置工具承载位置 | A 恢复 runtime-integration / B bootstrap 内 tool 域 | M4 | ✅ **已定案（2026-07-16）：方案 B**。工具目录与配置在 `bootstrap/tool/`（`ToolCatalog`、`ToolProperties`），M4 工具实现落 `tool/adapter/`；理由：V2 仅 7 个工具，不值一个独立模块 |
| 2 | `/api/tools` 冲突处理 | 对齐 fastclaw ToolsConfig / V2 不实现该路径 | M1（API 设计） | ✅ **已定案（2026-07-16）：V2 不实现该路径**。工具设置页的 404 属于已知契约缺口，留待 Phase 5.2 契约对账处理 |
| 3 | Phase 3 是否先行 | 建议先行（M1 直接按拆分后的 Repository 落地） | M1 | ✅ **已完成（commit dbb97af）**：OpenAgentStore 拆为 User/Provider/Agent/ChatSession 四仓储，seq 改 fastclaw 同款 INSERT 内原子分配 + RETURNING，种子剥离 DataSeeder，LOCAL_USER_ID 收敛 IdentityConstant，附 8 线程并发回归测试 |
