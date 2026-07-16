# OpenAgent Java 技术栈重构实施方案

> 版本：v1.0  
> 编制日期：2026-07-16  
> 源项目：`D:\resources\code\fastclaw`  
> 参考项目：`D:\resources\code\ragent`

## 1. 结论摘要

本次重构应采用“**保持 FastClaw 产品行为，以 ragent 的 Java 工程风格重新实现**”的路线，而不是把 FastClaw 改造成 RAG 系统，也不应逐文件把 Go 翻译成 Java。

推荐方案：

1. 使用 Java 17、Spring Boot 3.5.x、Maven 多模块、Spring MVC/SSE、MyBatis-Plus、Sa-Token、Redisson、OkHttp、MCP Java SDK，版本基线与 ragent 对齐。
2. 保留现有 Next.js 前端和 `/api/*`、`/v1/*` 协议，先替换后端，避免同时重写前后端扩大风险。
3. Agent 核心采用显式状态机和端口/适配器设计，不直接套用 ragent 的固定 RAG 流水线。ragent 的 `infra-ai`、统一返回、异常处理、用户上下文、SSE、配置属性和接口/实现分层可作为主要参考。
4. 第一阶段继续兼容 SQLite 和 PostgreSQL，沿用现有表结构及数据；新变更只做增量迁移，确保 Go 版本可回退。
5. 采用“特征测试 -> Java 纵向切片 -> 双实现比对 -> 灰度切换”的迁移方式。先打通登录、Agent CRUD、流式对话、单轮工具调用，再扩展沙箱、MCP、技能、渠道和多副本能力。

不建议一次性停机重写。FastClaw 的核心代码与状态模型复杂，直接大爆炸式替换很难验证会话隔离、工具循环、流式事件和多租户作用域是否保持一致。

## 2. 项目阅读结论

### 2.1 FastClaw 现状

FastClaw 是 Go 1.25 实现的 Agent Runtime/Agent Factory，前端是 Next.js 16 + React 19。后端不是单一聊天接口，而是一套完整的平台运行时。

代码规模抽样统计：

| 项目 | 数量 |
|---|---:|
| Go 文件 | 302 |
| Go 测试文件 | 79 |
| HTTP 路由注册 | 约 133 |
| 当前有效核心表 | 21 |
| `internal/agent` | 85 文件，约 19,840 行 |
| `internal/setup` | 28 文件，约 10,709 行 |
| `internal/store` | 17 文件，约 6,594 行 |
| `internal/channels` | 14 文件，约 4,718 行 |
| `internal/sandbox` | 13 文件，约 3,925 行 |
| `internal/gateway` | 11 文件，约 3,862 行 |

主要能力边界：

| 领域 | FastClaw 现有实现 |
|---|---|
| Agent 内核 | 多轮 LLM/Tool 循环、上下文构造、压缩、目标续跑、子 Agent、steer、事件流 |
| 模型接入 | OpenAI、Anthropic、Ollama 及 OpenAI-compatible，支持流式、工具、reasoning、usage |
| 多租户 | system/user/agent 配置继承，owner/viewer，公开 Agent，chatter 隔离 |
| 会话与记忆 | session/message/event、MEMORY.md、USER.md、自动持久化、来源标记 |
| 工具与技能 | 内置文件/命令/网络工具、技能发现与安装、Agent 私有覆盖 |
| 隔离执行 | Docker、E2B、BoxLite，workspace hydrate/sync，项目预览运行时 |
| 扩展 | MCP 客户端、JSON-RPC 子进程插件、hook/provider/channel/tool 扩展 |
| 渠道 | Web、Telegram、Discord、Slack、飞书、LINE、微信 |
| 平台能力 | 用户、API Key、配额、用量、Cron、Push、管理后台、OpenAI 兼容 API |
| 分布式 | PostgreSQL、Redis Stream、租约、S3 兼容对象存储、多 Pod 部署 |

核心数据表包括：`users`、`web_sessions`、`push_devices`、`apikeys`、`apikey_agents`、`agents`、`sessions`、`session_messages`、`session_events`、`agent_files`、`agent_knowledge_chunks`、`configs`、`cron_jobs`、`projects`、`project_runtimes`、`agent_goals`、`token_usage_daily`、`quotas`、`token_usage_log`、`channel_leases`、`channels`。

### 2.2 ragent 可参考的工程风格

ragent 使用 Java 17、Spring Boot 3.5.7 和 Maven 多模块，当前模块为：

| 模块 | 职责 |
|---|---|
| `framework` | 统一返回、异常体系、用户上下文、数据库公共配置、Redis、幂等、MQ、SSE 工具 |
| `infra-ai` | LLM/Embedding/Rerank/VLM 的接口、路由和 Provider 客户端 |
| `bootstrap` | 应用启动、Controller、Service、DAO、业务编排和配置 |
| `mcp-server` | 独立 MCP Server 示例/服务 |
| `frontend` | Vite + React 管理端 |

值得复用的设计：

- 父 POM 统一依赖版本和格式化规则。
- `Controller -> Service -> DAO/Mapper` 的常规业务分层。
- `LLMService -> RoutingLLMService -> ChatClient` 的模型门面与路由模式。
- `StreamCallback`、`StreamCancellationHandle`、`SseEmitterSender` 的流式输出与取消抽象。
- `Result`、`Results`、`GlobalExceptionHandler`、业务异常和错误码约定。
- Sa-Token、`UserContext`、Redis/Redisson、MyBatis-Plus 的基础设施组合。
- 配置通过 `@ConfigurationProperties` 管理，策略实现通过 Spring Bean 列表自动组装。

不能直接照搬的部分：

- ragent 的 `StreamChatPipeline` 是 RAG 固定阶段流水线；FastClaw 需要模型动态选择工具并多轮执行的 Agent loop。
- ragent 的 MCP 主要服务于检索阶段；FastClaw 的 MCP 是通用动态工具源。
- ragent 的业务主要围绕知识库、意图树、向量检索；这些不是 FastClaw 重构的目标领域。
- ragent 当前测试比例偏低，不能作为本次迁移的质量基线。

## 3. 重构目标与非目标

### 3.1 目标

1. Java 后端在关键用户流程和外部协议上与当前 Go 版本兼容。
2. 形成职责清晰、可单测、可扩展的 Agent Runtime，而不是把所有逻辑堆入 `bootstrap`。
3. 支持现有 SQLite 单机部署和 PostgreSQL + Redis + S3 多副本部署。
4. 保留现有 Next.js 管理端，原则上只修复不兼容点。
5. 建立契约测试、状态机测试、集成测试和迁移回滚机制。
6. 后续新增 Provider、Tool、Channel、Sandbox 时只需实现稳定扩展接口。

### 3.2 非目标

- 第一版不重写前端视觉和交互。
- 不将 FastClaw 改造成 ragent 的知识库问答产品。
- 不在迁移期同时引入微服务拆分、响应式全栈或新的消息中间件。
- 不要求 Java 类与 Go 文件一一对应。
- 第一版不替换 Python/Node 插件协议，也不改写现有技能内容。

## 4. 目标技术栈

| 分类 | 推荐技术 | 说明 |
|---|---|---|
| 语言/构建 | Java 17、Maven Wrapper、多模块 Maven | 与 ragent 一致 |
| Web | Spring Boot 3.5.x、Spring MVC、SseEmitter、WebSocket | 与现有阻塞式 SDK/渠道生态匹配 |
| 数据访问 | MyBatis-Plus + 原生 Mapper SQL | 简单 CRUD 用 MP，复杂作用域查询显式写 SQL |
| 数据库 | SQLite JDBC、PostgreSQL JDBC | 保留 FastClaw 两种运行模式 |
| 数据迁移 | Liquibase 或自研双方言 MigrationRunner | 首选 Liquibase；复杂数据回填使用 Java ChangeSet |
| 认证 | Sa-Token + BCrypt/现有哈希兼容适配器 | Cookie、Bearer API Key 分开处理 |
| HTTP/流式客户端 | OkHttp 4.12 | 复用 ragent 模式，统一超时、代理、取消和 SSE 解析 |
| JSON/YAML | Jackson、SnakeYAML | JSON 协议和技能元数据 |
| 分布式协调 | Redis + Redisson | 租约、锁、事件广播和限流 |
| 定时任务 | Spring TaskScheduler + DB 抢占/Redisson 锁 | 保持 DB 为任务事实来源 |
| MCP | `io.modelcontextprotocol.sdk` 1.1.x | 同时支持 stdio、HTTP/SSE/Streamable HTTP |
| CLI | Picocli | 命令结构清晰，可复用应用服务 |
| 可观测性 | Actuator、Micrometer、OpenTelemetry 接口 | 指标至少覆盖 LLM、工具、队列、渠道和沙箱 |
| 测试 | JUnit 5、Mockito、MockWebServer、Testcontainers、REST Assured | 覆盖单元、协议、数据库及端到端 |

关于 Agent 框架：第一版建议实现轻量 `AgentKernel`，暂不把 LangChain4j/Spring AI 作为核心编排器。原因是现有行为包含 Raw Assistant、reasoning、steer、目标续跑、工具恢复、事件持久化和特定上下文规则，通用框架很容易形成不可控的协议差异。可以在 `infra-ai` 中为 Spring AI/LangChain4j 预留适配接口，但不让其控制核心状态机。

## 5. Maven 模块设计

建议项目结构：

```text
openagent/
├─ pom.xml
├─ framework/             # 公共约定和 Spring 基础设施
├─ infra-ai/              # LLM Provider、模型路由、SSE 解析、usage
├─ agent-core/            # Agent 状态机、上下文、会话、记忆、工具端口
├─ runtime-integration/   # sandbox、workspace、skill、plugin、MCP、对象存储
├─ bootstrap/             # Web API、应用服务、DAO、渠道、Cron、装配与启动
├─ cli/                   # Picocli 管理命令和 daemon/service 包装
├─ frontend/              # 迁入或引用原 fastclaw/web，第一阶段尽量不改
├─ resources/
│  └─ database/           # SQLite/PostgreSQL schema 与变更集
└─ docs/
```

依赖方向必须保持单向：

```text
framework
  ↑       ↑
infra-ai  agent-core
     ↑      ↑
   runtime-integration
          ↑
       bootstrap

framework ← cli（cli 通过共享 application facade 或管理 API 工作）
```

约束：

- `agent-core` 只定义领域模型、状态机和端口，不引用 Spring MVC、MyBatis、渠道 SDK 或 Docker SDK。
- `infra-ai` 不读取 Controller DTO，不直接操作会话表。
- `runtime-integration` 实现 Tool、Sandbox、MCP、Plugin、Skill、Workspace 等端口。
- `bootstrap` 负责 Controller、鉴权、事务边界、DAO 和 Bean 装配，不承载 Agent loop 细节。
- 跨模块 DTO 分成 API DTO 与领域对象，禁止 Mapper DO 直接返回前端。

## 6. 核心设计

### 6.1 Agent 状态机

将当前隐式循环显式建模为：

```text
RECEIVED
  -> LOAD_SCOPE
  -> BUILD_CONTEXT
  -> CALL_MODEL
       -> EMIT_TEXT -> COMPLETE
       -> TOOL_REQUESTED
            -> AUTHORIZE_TOOL
            -> EXECUTE_TOOL
            -> PERSIST_OBSERVATION
            -> BUILD_CONTEXT -> CALL_MODEL
       -> COMPACT_CONTEXT -> CALL_MODEL
       -> WAIT_STEER -> BUILD_CONTEXT
  -> PERSIST_RESULT
  -> PUBLISH_EVENTS
```

核心接口建议：

```java
public interface AgentKernel {
    AgentRunHandle run(AgentRunCommand command, AgentEventSink sink);
}

public interface ModelGateway {
    ModelCallHandle stream(ModelRequest request, ModelEventSink sink);
}

public interface ToolRegistry {
    List<ToolDefinition> resolve(ToolScope scope);
}

public interface ToolExecutor {
    ToolResult execute(ToolCall call, ToolExecutionContext context);
}

public interface ConversationStore {
    ConversationSnapshot load(SessionRef session);
    void append(SessionEvent event);
}
```

必须保留的语义：

- 同一会话的运行串行化；不同会话可并行。
- `maxToolIterations`、取消、超时、steer、异常恢复均是显式状态转换。
- 模型输出事件先落 `session_events` 再向 SSE/WebSocket/渠道发布，避免断连后状态丢失。
- 工具授权在调用前完成，策略拒绝也作为 observation 返回模型。
- 上下文压缩不能丢失 tool call/result 配对、reasoning 标记和消息 origin。
- 每次运行携带不可变的 `userId/ownerId/chatterUserId/agentId/sessionKey/channel/accountId/chatId`。

### 6.2 配置作用域

保留 FastClaw 的 system -> user -> agent 继承与同名覆盖规则，建立单独的 `ScopedConfigService`：

```text
请求身份
  -> system 配置
  -> 当前 chatter/user 配置覆盖
  -> agent 配置覆盖
  -> 若 public agent 且 shareModelConfig=true，按现有规则引入 owner 模型配置
  -> 形成不可变 EffectiveAgentConfig
```

禁止业务代码自行拼接作用域 SQL。所有 Provider、Skill、Channel、Agent defaults 都通过同一解析器获取，并用兼容测试覆盖继承顺序与密钥可见性。

### 6.3 模型 Provider

参考 ragent 的接口/路由结构，但补足 Agent 场景所需能力：

- `ChatClient`：Provider 单次流式调用。
- `RoutingLLMService`：根据 `provider/model` 选择客户端、处理健康状态和回退。
- `ModelEvent`：`TEXT_DELTA`、`REASONING_DELTA`、`TOOL_CALL_DELTA`、`USAGE`、`MESSAGE_END`、`ERROR`。
- `ModelMessage`：保留文本、图片、附件引用、tool call、tool result、raw assistant 扩展字段。
- OpenAI-compatible 与 Anthropic 分别实现协议适配，禁止用字符串替换模拟协议转换。
- Provider 重试只允许发生在尚未向用户发出首包且请求可安全重放时。

首批实现 OpenAI-compatible 和 Anthropic；Ollama/OpenRouter/Groq/DeepSeek/Mistral 通过 OpenAI-compatible 配置覆盖。每个客户端使用录制的 SSE fixture 做解析契约测试。

### 6.4 会话、事件和实时输出

- `sessions` 保存会话元数据；`session_messages` 保存规范化消息；`session_events` 保存可重放事件。
- Web 聊天继续输出现有 SSE 事件；`/v1/chat/completions` 严格输出 OpenAI SSE chunk 和 `[DONE]`。
- 使用 `SseEmitter`，封装成类似 ragent `SseEmitterSender` 的发送器，但补充背压队列、心跳、断连取消和完成幂等。
- 单机使用内存 EventHub；多副本使用 Redis Stream/Redis PubSub 适配器，并保留 DB 事件作为事实来源。
- 每个 run 生成 `runId`，日志、usage、tool span、session event 使用同一关联 ID。

### 6.5 工具、技能、MCP 和插件

统一抽象为 `ToolDescriptor + ToolInvoker`，来源字段区分：`BUILTIN`、`SKILL`、`MCP`、`PLUGIN`。

- 内置工具：文件、命令、Web fetch/search、memory、goal、cron、message 等逐项迁移。
- 技能：继续读取 `SKILL.md` 和现有目录；配置解析使用 YAML parser，不做正则拼装。
- MCP：Java MCP SDK 管理连接生命周期，支持 stdio 和 HTTP；工具变化触发 Registry 原子刷新。
- 插件：保持现有 `plugin.json` + JSON-RPC 子进程协议，通过 `ProcessBuilder` 实现，不要求插件作者改写。
- 所有工具统一经过 schema 校验、策略判断、超时、输出截断、敏感信息清洗和审计。
- Java 进程不得直接执行未经 sandbox policy 判断的 shell 命令。

### 6.6 Sandbox 与 Workspace

定义 `SandboxBackend`：

```java
public interface SandboxBackend {
    SandboxLease acquire(SandboxSpec spec);
    ExecResult execute(SandboxLease lease, ExecCommand command);
    void upload(SandboxLease lease, WorkspaceDelta delta);
    WorkspaceDelta downloadChanges(SandboxLease lease);
    void release(SandboxLease lease);
}
```

实现顺序：Docker -> E2B -> BoxLite。Docker 先用受控 CLI/HTTP client 完成，所有路径先规范化并验证位于 workspace 根下；hydrate、工具执行后同步、休眠/唤醒、预览端口和日志流分别测试。对象存储仍采用 S3 兼容 API，保留本地文件系统实现。

### 6.7 渠道与调度

统一渠道接口：

```java
public interface ChannelAdapter {
    ChannelType type();
    void validate(ChannelConfig config);
    void start(ChannelBinding binding, InboundMessageSink sink);
    SendResult send(OutboundMessage message);
    void stop(ChannelBinding binding);
}
```

迁移顺序建议：Web -> Telegram -> 飞书 -> Discord -> Slack -> LINE -> 微信。每个入站事件先转换成统一 `InboundMessage`，去重后再进入 Gateway。渠道租约必须在多副本下保证同一账号只有一个消费者。

Cron 以 DB 为事实来源，通过 `next_run` 扫描和分布式抢占执行；任务执行需有幂等键、失败计数、指数退避和最大失败策略，不能仅依赖单机 `@Scheduled` 内存状态。

## 7. Go 到 Java 的能力映射

| Go 包 | Java 目标位置 | 实现策略 |
|---|---|---|
| `internal/agent` | `agent-core` | 显式 Agent 状态机、上下文、记忆、goal、事件 |
| `internal/provider` | `infra-ai` | 扩展 ragent ChatClient/LLMService 模式 |
| `internal/store` | `bootstrap` DAO + `framework` DB | 复用表结构，MyBatis Mapper + 双方言 SQL |
| `internal/setup` | `bootstrap` | Controller、应用服务、DTO、鉴权与装配 |
| `internal/api` | `bootstrap.api.openai` | 保持 `/v1` OpenAI 兼容契约 |
| `internal/gateway` | `bootstrap.gateway` | 入站归一化、路由、去重、EventHub |
| `internal/session` | `agent-core` + DAO adapter | 会话锁、manager、steer、store adapter |
| `internal/sandbox` | `runtime-integration.sandbox` | Docker/E2B/BoxLite backend |
| `internal/workspace` | `runtime-integration.workspace` | local/S3、hydrate/sync、metering |
| `internal/mcp` | `runtime-integration.mcp` | MCP Java SDK client manager |
| `internal/plugin` | `runtime-integration.plugin` | 保持 JSON-RPC 子进程协议 |
| `internal/skills` | `runtime-integration.skill` | 目录、tarball、GitHub/ClawHub 安装 |
| `internal/channels` | `bootstrap.channel` | 各 SDK adapter + lease |
| `internal/cron` | `bootstrap.scheduler` | DB 调度 + 分布式抢占 |
| `internal/auth/users/usage` | `bootstrap.identity/billing` | Sa-Token、API Key、quota、usage |
| `internal/policy/privacy` | `agent-core.policy` | 工具策略、环境清洗、输出扫描 |
| `internal/bus/rediscoord` | `runtime-integration.coordination` | local/Redis 两套实现 |
| `cmd/fastclaw` | `cli` | Picocli；共享应用服务，保留命令语义 |

## 8. API 与前端兼容策略

### 8.1 契约优先级

P0 必须完全兼容：

- `/api/login`、`/api/logout`、`/api/me`、`/api/status`
- `/api/agents*`、`/api/config`、`/api/providers*`
- `/api/chat/stream`、`/api/chat/subscribe`、会话列表/历史/重命名/删除
- `/v1/chat/completions`、`/v1/agents`、`/v1/users`、`/v1/usage`、`/v1/quota`
- `/healthz`、`/livez`、`/readyz`

P1 管理能力：Skills、MCP/Tools、Plugins、API Keys、Users、Usage、Cron、Knowledge files、Projects。

P2 外部集成：各 IM Channel、Push、Webhooks、runtime preview、多 Pod 协调。

### 8.2 兼容方法

1. 从 Go 路由注册自动生成 endpoint inventory，记录 method/path/auth/role/request/response/SSE event。
2. 针对现有 Next.js `api.ts` 建立 TypeScript 类型和 Java DTO 的 JSON fixture 测试。
3. 用相同请求分别调用 Go 与 Java 实现，规范化时间、ID、token 后做结构化 diff。
4. HTTP 状态码、字段命名、空值规则、Cookie 属性、SSE event 顺序均纳入契约；不能只比较“接口能返回”。
5. Java 切换前，现有前端构建和 Playwright 核心流程必须在 Java 后端上通过。

## 9. 数据迁移与回滚

### 9.1 基本原则

- 第一版尽量直接映射现有 21 张表，不先做“理想化表设计”。
- 所有 schema 变更只新增表、列或索引；切换稳定前不删除、不改名。
- SQLite 和 PostgreSQL 各自建立 migration 集，使用同一逻辑版本号。
- `configs.data` 等 JSON 数据先保持现有形状，由兼容 DTO 解析。
- 密码哈希、API Key hash/prefix、用户 ID、Agent ID、session key 全部原值保留。
- skill/workspace/object storage 路径保持不变。

### 9.2 切换步骤

1. 在脱敏数据库副本运行 Java 读写兼容测试和迁移演练。
2. 上线 Java shadow 实例，只镜像无副作用的 GET 和模型请求解析，不发送渠道消息、不执行工具。
3. 发布前进入短暂写入维护窗口，停止 Go scheduler/channel consumer。
4. 备份数据库、`FASTCLAW_HOME` 和对象存储清单，记录校验和与行数。
5. 执行增量 migration 和数据校验。
6. 启动 Java，先通过 readiness、登录、Agent CRUD、流式聊天、工具和 Cron smoke test，再开放流量。
7. 观察错误率、首包延迟、完成率、usage 差异和队列积压。

回滚条件：P0 接口错误率明显升高、消息重复/丢失、会话越权、usage 大幅偏差或渠道租约冲突。由于切换期只允许增量 schema，回滚时可停止 Java、恢复 Go consumer 并切回旧服务；如发生数据写坏则恢复备份并重放经过审计的切换期请求。

## 10. 分阶段实施计划

### 阶段 0：行为基线与脚手架（1-2 周）

交付：

- Maven 父工程和六个模块骨架。
- Endpoint、表、配置项、CLI 命令、工具、渠道、SSE event 清单。
- 从 Go 测试提取的 characterization fixtures。
- CI：格式化、单测、SQLite、PostgreSQL/Redis Testcontainers。

退出条件：P0 契约全部有可执行测试或明确 fixture，模块依赖规则由 ArchUnit 校验。

### 阶段 1：基础设施与身份数据（2-3 周）

交付：

- 双数据库连接、现有表 Mapper、migration baseline。
- 统一结果/异常、Sa-Token 登录、Cookie、API Key、用户上下文。
- system/user/agent 配置作用域解析。
- Agent、Provider、用户、API Key 基础管理 API。

退出条件：同一数据库副本上，Go/Java 的登录权限、Agent 列表和有效配置结果一致；越权测试全部通过。

### 阶段 2：首个可用纵向切片（3-4 周）

交付：

- OpenAI-compatible、Anthropic Provider 和流式协议解析。
- 会话/message/event 持久化。
- AgentKernel 基础循环、上下文构造、文本输出、单/多轮工具调用。
- `/api/chat/stream` 与 `/v1/chat/completions` 流式/非流式接口。
- 基础内置工具：文件读取/写入/list、web fetch、memory。

退出条件：现有前端可登录并完成连续对话；流式事件、工具调用、取消、usage 和历史重载契约通过。

### 阶段 3：Agent 完整语义（3-4 周）

交付：

- context compaction、reasoning/raw assistant、附件、视觉消息。
- steer、goal continuation、subagent、heartbeat、自动记忆持久化。
- 完整 ToolRegistry、policy、隐私清洗、失败恢复。
- session 并发控制与事件重放。

退出条件：逐项迁移 `internal/agent` 的高价值测试；同会话并发、工具超限、断流恢复和上下文压缩无数据破坏。

### 阶段 4：运行时扩展（3-4 周）

交付：

- Skills 安装/加载/作用域覆盖。
- MCP stdio/HTTP、插件 JSON-RPC。
- Docker sandbox、workspace hydrate/sync、本地/S3 存储。
- Projects、runtime preview、日志和 changed files。

退出条件：代码执行只发生在授权 sandbox；现有 demo skill/plugin/MCP 可无修改运行；workspace 同步故障可恢复。

### 阶段 5：平台管理、调度与渠道（3-5 周）

交付：

- Cron、usage、quota、push、admin/task/eval。
- 渠道按 Web、Telegram、飞书、Discord、Slack、LINE、微信顺序接入。
- Redis bus、channel lease、多副本消息路由。
- CLI、daemon/service 脚本和部署文件。

退出条件：渠道重复消息测试、Cron 唯一执行、多副本故障转移和配额并发扣减通过。

### 阶段 6：灰度与下线 Go（2-3 周）

交付：

- Go/Java differential report、性能报告、安全回归报告。
- 数据迁移与回滚 runbook。
- Docker、Helm、Kubernetes、升级文档。
- 灰度、全量切换和 Go 版本归档。

退出条件：连续两个发布周期无 P0/P1 兼容问题，关键 SLO 达标，完成一次可验证的回滚演练。

按 4 名熟悉 Java/Spring 的后端、1 名前端/测试协作估算，完整等价迁移约 17-25 周；只做到 P0 可用纵向切片约 8-12 周。该区间需在阶段 0 完成契约清点后重新估算。

## 11. 测试与质量门禁

### 11.1 测试层次

| 层次 | 重点 |
|---|---|
| 单元测试 | 状态转换、配置继承、权限、Tool schema、SSE parser、上下文压缩 |
| 组件测试 | Provider MockWebServer、Plugin 子进程、MCP test server、Sandbox fake |
| 数据库测试 | SQLite 临时库；PostgreSQL/Redis/S3 使用 Testcontainers |
| 契约测试 | Go/Java HTTP JSON、Cookie、SSE、WebSocket、错误响应对比 |
| 端到端 | 登录 -> 创建 Agent -> 配 Provider -> 对话 -> 工具 -> 历史 -> usage |
| 故障测试 | 模型断流、工具超时、Redis 中断、Pod 重启、渠道重复投递、磁盘满 |
| 安全测试 | IDOR、跨 tenant 数据读取、路径穿越、命令注入、SSRF、密钥泄漏 |

### 11.2 发布硬门禁

- P0 契约 100% 通过，P1 不低于 95%，其余差异有显式豁免记录。
- `agent-core` 状态机和作用域/权限代码分支覆盖率不低于 85%。
- SQLite 与 PostgreSQL 均通过 schema、CRUD、并发和迁移测试。
- 同一会话压力下无消息乱序、重复 tool result 或跨用户串话。
- `/v1/chat/completions` 能被标准 OpenAI SDK 的流式和非流式客户端调用。
- 前端生产构建通过，核心流程 Playwright 测试通过。
- 关键延迟相对 Go 基线：首包 P95 劣化不超过 20%，平台自身额外开销不超过 100 ms；最终阈值以阶段 0 实测修订。

## 12. 可观测性与运行指标

至少记录：

- HTTP：请求量、状态码、P50/P95/P99、当前 SSE 连接。
- Agent：run 数、完成率、取消率、平均迭代数、compaction 次数、steer 等待数。
- LLM：Provider/model、首包耗时、总耗时、输入/输出/cache token、重试和错误类型。
- Tool：调用量、授权拒绝、耗时、超时、输出截断、sandbox backend。
- Session：锁等待、事件落库延迟、重放失败、活跃会话。
- Channel/Cron：入站去重、发送失败、租约冲突、任务延迟和重复执行。
- 基础设施：DB pool、Redis、对象存储、子进程数、sandbox 数和队列深度。

日志禁止输出 API Key、Provider 密钥、渠道 token、完整 Authorization header 和未清洗的工具环境变量。

## 13. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Agent loop 行为漂移 | 回答质量、工具调用和记忆变化 | 显式状态机 + Go fixture + differential test |
| SSE/断连语义差异 | 前端卡住、重复输出 | 事件落库优先、发送完成幂等、断连/重连测试 |
| 多租户作用域错误 | 严重数据与密钥泄漏 | 单一 ScopeResolver、不可变请求上下文、系统化越权测试 |
| SQLite/PostgreSQL 方言差异 | 单机或云部署不可用 | 双方言 CI，不在业务层拼方言 SQL |
| Java 阻塞线程耗尽 | 高并发长连接性能下降 | 独立有界线程池、连接上限、背压；后续再评估 Java 21 虚拟线程 |
| Sandbox 路径/命令安全 | 主机被访问或数据泄漏 | 路径规范化、默认拒绝 policy、最小权限容器、审计 |
| 渠道重复消费 | 重复回复和费用增加 | DB 幂等键 + Redis lease + at-least-once 去重 |
| CLI/daemon 跨平台差异 | 运维体验退化 | Picocli + Windows/Linux/macOS 脚本矩阵和服务安装测试 |
| 范围持续膨胀 | 迁移无法按期切换 | P0/P1/P2 分级，先纵向切片，新增需求进入 Go/Java 双实现成本评审 |

## 14. 首批工程任务拆分

建议立即创建以下 Epic：

1. `E0-契约基线`：路由/表/配置/事件/命令清单及 Go fixture runner。
2. `E1-Java脚手架`：父 POM、模块、CI、ArchUnit、统一异常和配置。
3. `E2-身份与作用域`：用户、登录、API Key、Agent access、ScopedConfig。
4. `E3-模型网关`：OpenAI/Anthropic、SSE parser、usage、取消和重试。
5. `E4-会话事件`：session/message/event Mapper、锁和事件总线。
6. `E5-AgentKernel`：状态机、上下文、工具循环、compaction。
7. `E6-P0 API`：管理端聊天和 OpenAI compatible API。
8. `E7-基础工具`：文件、Web、memory、policy 和审计。
9. `E8-前端验收`：现有 Next.js 接 Java 后端的 Playwright 流程。

阶段 0 结束时应产出一张完整的“能力-接口-数据表-测试-负责人”追踪矩阵。任何 Go 模块只有在矩阵中对应的 Java 实现、契约测试、数据校验和运维文档全部完成后，才能标记为已迁移。

## 15. 最终验收定义

重构完成必须同时满足：

1. 现有 FastClaw 前端无需大规模修改即可运行。
2. P0/P1 API、OpenAI 兼容流式协议和 CLI 关键命令达到约定兼容度。
3. 现有用户、Agent、会话、配置、技能、用量和任务数据无损迁移。
4. 单机 SQLite 与多副本 PostgreSQL + Redis 两种部署均通过验收。
5. Agent 多轮工具、记忆、steer、goal、sandbox、MCP/plugin 和渠道按范围清单通过测试。
6. 有明确性能基线、监控告警、升级步骤和可演练回滚方案。
7. Go 服务下线后保留只读归档和至少一个发布周期的紧急回切能力。

只有“Java 服务能启动并回答一次问题”不算重构完成；外部协议、状态一致性、租户隔离、运行安全和可回滚性共同构成最终完成标准。
