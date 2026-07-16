# OpenAgent Java 重构 V1 实施方案

> 版本：V1.0  
> 编制日期：2026-07-16  
> 总体方案：[OPENAGENT_JAVA_REFACTORING_PLAN.md](OPENAGENT_JAVA_REFACTORING_PLAN.md)  
> 前端策略：直接复用 `D:\resources\code\fastclaw\web`，不重做 UI

## 1. V1 定位

V1 定义为一个可部署、可迁移、可供真实用户使用的“**单机 Web Agent 平台**”，目标是完成 FastClaw Go 后端到 Java 后端的第一批生产替换，而不是一次性覆盖全部渠道和云原生能力。

V1 必须形成以下闭环：

```text
管理员初始化/登录
  -> 配置模型 Provider
  -> 创建并配置 Agent
  -> 管理 Agent 身份文件和技能
  -> 用户通过 Web 或 OpenAI-compatible API 发起对话
  -> Agent 流式调用模型并按需执行工具
  -> 会话、事件、记忆和用量持久化
  -> 管理员在原有前端查看和管理结果
```

V1 的判断标准不是“Java 服务能够回答一句话”，而是上述闭环可以在现有前端中稳定运行，数据能从 Go 版本迁移，并具备回滚能力。

## 2. 关键决策

| 决策项 | V1 选择 |
|---|---|
| Java 基线 | Java 17、Spring Boot 3.5.7 |
| 工程形式 | Maven 多模块，参考 ragent 分层风格 |
| 前端 | 直接复用 FastClaw Next.js 16 + React 19 前端 |
| Web 技术 | Spring MVC + `SseEmitter`，不引入 WebFlux |
| Agent 编排 | 自研显式 `AgentKernel` 状态机，不使用固定 RAG Pipeline |
| AI 客户端 | 参考 ragent `infra-ai`，使用 OkHttp 实现 Provider 协议 |
| 数据访问 | MyBatis-Plus + 显式 Mapper SQL |
| 数据库 | SQLite 与 PostgreSQL 均支持 |
| Redis | V1 不强依赖；保留端口，只实现单机适配器 |
| 命令执行 | 仅允许通过 Docker Sandbox；未启用时不注册 `exec` |
| MCP | 支持 stdio 和 HTTP 客户端 |
| 认证 | Sa-Token + 现有密码/API Key 哈希兼容层 |
| 数据迁移 | 沿用现有表结构，只做增量变更，保证 Go 可回滚 |
| 部署 | 单 Java 服务 + 复用前端 + SQLite/PostgreSQL + 可选 Docker |

## 3. V1 范围

### 3.1 必须交付

#### 身份与权限

- 首次初始化和管理员账号创建。
- 用户名/密码登录、退出、当前用户、修改资料和密码。
- `super_admin`、`user`、`app_user` 角色兼容。
- Cookie 登录态和 Bearer API Key 两套认证入口。
- API Key 创建、删除、轮换及 Agent 授权范围。
- Agent owner/viewer、public access 和 chatter 数据隔离。
- system -> user -> agent 配置继承和 `shareModelConfig` 规则。

#### Agent 管理

- Agent 列表、详情、创建、修改、删除。
- 模型、temperature、max tokens、max tool iterations、prompt mode 配置。
- `SOUL.md`、`IDENTITY.md`、`MEMORY.md`、`USER.md` 等系统文件读写。
- Agent 文件上传、下载、列表和删除。
- Agent 公开访问开关及访问控制。
- bundled skill、Agent 私有 skill、skill 启停和环境配置。
- Skill ZIP 上传安装；ClawHub/GitHub 搜索安装延期到 V1.1。

#### 模型 Provider

- OpenAI-compatible Provider。
- Anthropic Provider。
- Ollama、OpenRouter、Groq、DeepSeek、Mistral 通过 OpenAI-compatible 配置接入。
- Provider CRUD、连通性测试、模型列表和 Agent 默认模型。
- 文本、图片 URL/data URL、tool call/tool result、reasoning、usage。
- 流式取消、首包前安全重试、超时和错误归一化。

#### 对话与会话

- Web 流式聊天。
- OpenAI-compatible `/v1/chat/completions` 流式和非流式调用。
- 会话列表、历史、重命名、删除。
- `session_messages` 与 `session_events` 持久化。
- 同一会话串行执行、不同会话并行执行。
- SSE 断开后的运行取消和已落库事件保留。
- 上下文窗口裁剪与基础 compaction。
- `MEMORY.md`、`USER.md` 注入与手动更新。
- 基础 steer；复杂 goal continuation 延期。

#### Agent 工具循环

- 模型原生 tool call 解析。
- 多轮 `model -> tool -> observation -> model` 循环。
- 最大迭代次数、工具超时、错误恢复和结果截断。
- 基础工具：`read_file`、`write_file`、`list_dir`、`apply_patch`、`web_fetch`、`web_search`、`memory_search`。
- `exec` 仅通过 Docker Sandbox 注册和执行。
- 工具路径限制在当前 Agent workspace 内。
- 工具 schema 校验、策略授权、敏感环境变量清洗和审计日志。

#### MCP

- MCP Server 配置、连接测试和工具发现。
- stdio MCP 客户端。
- HTTP/Streamable HTTP MCP 客户端。
- MCP 工具动态注册、调用、超时和断线重连。
- MCP 配置按 system/user/agent 作用域解析。

#### 数据与平台能力

- 兼容现有 21 张核心表。
- SQLite 单机模式和 PostgreSQL 模式。
- 用户、Agent、会话、配置、API Key、用量数据迁移。
- Token usage 记录与查询。
- 用户/Agent 基础配额校验。
- `/healthz`、`/livez`、`/readyz` 和 Actuator 指标。
- 结构化日志和 `requestId/runId/sessionKey` 关联。

### 3.2 有限实现

| 能力 | V1 实现边界 |
|---|---|
| Skills | 本地、bundled、ZIP 上传；不做远程市场搜索安装 |
| Sandbox | 只实现 Docker；不实现 E2B、BoxLite |
| Knowledge | 支持知识文件和文本 memory search；不建设完整向量 RAG |
| Web search | 先支持一个 HTTP provider，其他 provider 后续补充 |
| Cron | 单机基础创建、启停、删除和执行，不承诺多副本唯一执行 |
| CLI | 初始化、启动、状态、Agent/Provider 基础管理 |
| 前端 | 原代码直接复用；允许 API 兼容修复和功能开关，不做设计重构 |

### 3.3 V1 不交付

- Telegram、Discord、Slack、飞书、LINE、微信渠道。
- APNs Push 和外部 Webhook Server。
- Python/Node JSON-RPC Plugin 运行时及 Hook Plugin。
- E2B、BoxLite Sandbox。
- Project Runtime、Preview、changed files、端口代理。
- Redis Stream、Channel lease、多 Pod 和 S3 workspace 分布式同步。
- Team chat、Subagent、完整 Goal continuation、自动 heartbeat。
- Agent 自动学习 skill、完整 evaluation runner。
- 前端框架迁移、页面重做和视觉改版。

上述接口如被现有前端访问，后端应返回稳定的空列表、能力状态或明确的 `FEATURE_NOT_AVAILABLE`，不能返回 404 或导致页面崩溃。前端通过最小功能开关隐藏尚未交付的入口。

## 4. V1 工程结构

```text
openagent/
├─ pom.xml
├─ framework/             # 统一返回、异常、认证上下文、DB、SSE、审计
├─ infra-ai/              # LLMService、Provider 路由、SSE parser、usage
├─ agent-core/            # AgentKernel、上下文、会话模型、工具循环、策略端口
├─ runtime-integration/   # 内置工具、Docker、Workspace、Skill、MCP
├─ bootstrap/             # Controller、Service、DAO、Cron、装配、启动类
├─ cli/                   # Picocli 命令
├─ frontend/              # 复用 fastclaw/web
└─ resources/database/
   ├─ sqlite/
   └─ postgresql/
```

V1 不拆微服务。所有 Java 模块最终由 `bootstrap` 打包成一个 Spring Boot 应用，减少部署、事务和调试复杂度。

模块约束：

- `agent-core` 不依赖 Spring MVC、MyBatis、渠道 SDK 或 Docker SDK。
- `infra-ai` 不依赖 Controller DTO，不直接访问会话表。
- `runtime-integration` 实现 `agent-core` 定义的工具、Sandbox、Skill 和 MCP 端口。
- `bootstrap` 负责 API、事务、DAO 和 Bean 装配，不承载 Agent loop。

## 5. 核心接口设计

### 5.1 Agent Kernel

```java
public interface AgentKernel {
    AgentRunHandle run(AgentRunCommand command, AgentEventSink eventSink);
}

public record AgentRunCommand(
        String runId,
        RequestIdentity identity,
        AgentId agentId,
        SessionRef session,
        UserInput input,
        EffectiveAgentConfig config) {
}
```

V1 状态：

```text
RECEIVED
  -> LOAD_CONTEXT
  -> CALL_MODEL
  -> TOOL_REQUESTED -> AUTHORIZE -> EXECUTE -> PERSIST -> CALL_MODEL
  -> COMPACT_CONTEXT -> CALL_MODEL
  -> COMPLETE / CANCELLED / FAILED
```

每次状态变化产生领域事件。持久化成功后再推送 SSE，保证断连时数据库中仍有可追踪记录。

### 5.2 AI 基础设施

```java
public interface LLMService {
    ModelCallHandle streamChat(ModelRequest request, ModelEventListener listener);
}

public interface ChatClient {
    boolean supports(ProviderType providerType);
    ModelCallHandle stream(ModelRequest request, ModelEventListener listener);
}
```

统一事件类型：`TextDelta`、`ReasoningDelta`、`ToolCallDelta`、`UsageReceived`、`MessageCompleted`、`ModelFailed`。

### 5.3 工具接口

```java
public interface ToolProvider {
    List<ToolDescriptor> listTools(ToolScope scope);
}

public interface ToolInvoker {
    ToolResult invoke(ToolCall call, ToolExecutionContext context);
}
```

所有 `ToolInvoker` 外层统一包装：参数校验 -> Policy -> 超时 -> 执行 -> 截断/清洗 -> 审计。

### 5.4 配置解析

```java
public interface ScopedConfigService {
    EffectiveAgentConfig resolve(RequestIdentity identity, AgentId agentId);
}
```

Controller、AgentKernel 和 Tool 不得直接查询 `configs` 表，避免不同业务路径出现不同继承顺序。

## 6. V1 API 清单

### 6.1 P0：必须完全兼容

| 领域 | 接口 |
|---|---|
| 健康 | `GET /healthz`、`GET /livez`、`GET /readyz` |
| 初始化 | `GET /api/status`、`POST /api/onboard` |
| 认证 | login/logout/me/profile/password |
| Agent | `GET/POST /api/agents`、`GET/PUT/DELETE /api/agents/{id}` |
| Agent 配置 | config、系统文件和普通文件相关接口 |
| Provider | providers CRUD、连通性测试、`GET/POST /api/config` |
| Chat | `/api/chat/stream`、`/api/chat/steer`、`/api/chat/subscribe` |
| Session | sessions/history/list/rename/delete |
| Skills | list/upload/delete、Agent skills list/delete |
| MCP/Tools | tools list、MCP 配置、发现和测试接口 |
| API Key | list/create/delete/rotate/Agent scope |
| Usage | `/api/usage`、`/api/agents/{id}/usage` |
| OpenAI API | `/v1/chat/completions`、`GET /v1/agents` |
| 上游用户 | `/v1/users`、`/v1/usage`、`/v1/quota` |

### 6.2 P1：页面不崩溃、基础能力可用

- 用户管理基础 CRUD。
- Cron 基础 CRUD。
- Knowledge files 基础存取。
- 公共 Agent/Skill 列表。
- 注册开关。

### 6.3 延期接口处理

Channels、Plugins、Projects、Runtime、Push、Team chat 等接口保留路由占位。GET 返回空集合和 `supported=false`，变更类请求返回统一业务错误：

```json
{
  "code": "FEATURE_NOT_AVAILABLE",
  "message": "该能力将在后续版本提供",
  "data": null
}
```

HTTP 状态和返回包裹形式最终以现有前端实际消费方式为准，契约测试固定后不得随意调整。

## 7. 前端复用方案

前端从 `fastclaw/web` 原样迁入 `frontend`，保留 Next.js/React/Tailwind 版本、页面路由、组件、样式、`src/lib/api.ts` 和 SSE 逻辑。

允许的修改仅包括：

1. Java 后端与 Go 返回差异造成的兼容修复。
2. 通过 `/api/status` capability flags 隐藏 V1 未实现入口。
3. 开发代理、构建目录和部署配置调整。
4. 新增 Playwright 核心流程测试。

建议 capability 响应：

```json
{
  "capabilities": {
    "channels": false,
    "plugins": false,
    "projectRuntime": false,
    "multiPod": false,
    "dockerSandbox": true,
    "mcp": true,
    "cron": true
  }
}
```

禁止为了配合 Java 后端批量重命名前端字段；Java DTO 使用 `@JsonProperty` 兼容现有协议。

## 8. 数据兼容方案

### 8.1 数据表优先级

- P0：`users`、`web_sessions`、`apikeys`、`apikey_agents`、`agents`、`sessions`、`session_messages`、`session_events`、`agent_files`、`configs`、`token_usage_daily`、`token_usage_log`、`quotas`。
- P1：`agent_knowledge_chunks`、`cron_jobs`、`agent_goals`。
- 仅兼容保留：`push_devices`、`projects`、`project_runtimes`、`channel_leases`、`channels`。

### 8.2 迁移规则

- 不修改现有主键格式和业务唯一键。
- 验证并兼容 Go 版本已有密码 hash。
- API Key 继续只保存 hash 和 prefix，不保存明文。
- `configs.data` 保持原 JSON 结构和 camelCase 字段。
- 时间统一使用 UTC 入库，API 按原格式序列化。
- 只允许新增列、表、索引；V1 稳定前不删除旧字段。
- SQLite/PostgreSQL 执行同一逻辑版本 migration，并校验行数、关系和抽样内容。

### 8.3 切换与回滚

1. 复制真实数据到测试环境，完成至少两次迁移演练。
2. 停止 Go 写入、Cron 和外部消费者。
3. 备份数据库及 `FASTCLAW_HOME`。
4. 启动 Java 进行只读校验。
5. 执行登录、Agent、配置、历史和流式对话 smoke test。
6. 开放 Java 写流量。
7. V1 只做增量 schema，回滚时停止 Java 并重新启动 Go。

## 9. 开发里程碑

### M0：基线和脚手架（第 1 周）

- 多模块工程、CI、格式化和 ArchUnit。
- Go endpoint、表、SSE event、配置继承清单。
- 现有前端迁入并完成生产构建。
- SQLite/PostgreSQL 测试环境和契约测试框架。

退出条件：Java 空应用可启动；前后端代理可用；契约测试能同时调用 Go/Java。

### M1：身份、数据和管理闭环（第 2-3 周）

- 统一返回、异常、用户上下文。
- 登录、用户、API Key、Agent CRUD。
- 双数据库 Mapper、配置作用域、Provider 管理。

退出条件：前端可以初始化、登录、管理 Agent/Provider；关键查询与 Go 一致。

### M2：模型与基础聊天（第 4-5 周）

- OpenAI-compatible、Anthropic 流式客户端。
- 会话/message/event 持久化。
- AgentKernel 无工具对话。
- Web SSE 和 `/v1/chat/completions`。

退出条件：多轮聊天、断连取消、历史恢复、usage 和标准 OpenAI SDK 调用通过。

### M3：工具、技能与 MCP（第 6-8 周）

- 多轮工具状态机和基础工具。
- Docker Sandbox 和 workspace 安全边界。
- 本地/ZIP skill、stdio/HTTP MCP。
- 基础 compaction、memory 和 steer。

退出条件：完成模型选工具、Docker 执行、结果回传、模型回答闭环；越权、超时和路径穿越测试通过。

### M4：前端联调和平台能力（第 9-10 周）

- API Key、usage、quota、knowledge、基础 Cron 页面联调。
- capability flags 和延期页面处理。
- CLI 基础命令、Actuator、指标、审计和部署文件。

退出条件：V1 范围页面可用，范围外入口不暴露或有稳定提示，不出现 404/页面异常。

### M5：迁移、压测和灰度（第 11-12 周）

- Go/Java differential report。
- 数据迁移和回滚 runbook。
- 安全、性能、故障测试。
- 灰度发布和问题修复。

退出条件：全部发布门禁通过，完成一次真实环境切换和回滚演练。

## 10. 团队建议

以 12 周为目标：

| 角色 | 人数 | 主要职责 |
|---|---:|---|
| Java/架构负责人 | 1 | AgentKernel、作用域、安全边界、评审 |
| Java 后端 | 2-3 | API、数据、Provider、MCP、Sandbox |
| 前端 | 0.5-1 | 迁入、兼容修复、能力开关、E2E |
| 测试/质量 | 1 | 契约、迁移、性能、安全、回归 |
| 运维支持 | 0.5 | Docker、部署、监控、灰度、回滚 |

若只有 2 名后端，应延长到 16-20 周，或把 MCP、Cron、PostgreSQL 其中一项移到 V1.1；不建议通过减少租户隔离、契约测试或 Docker 安全边界压缩周期。

## 11. V1 发布门禁

### 功能

- 现有前端完成初始化、登录、Provider 配置、Agent 管理和多轮聊天。
- 标准 OpenAI SDK 可流式和非流式调用。
- OpenAI-compatible、Anthropic 通过 fixture 和真实 smoke test。
- 工具循环、Docker exec、Skill 和 MCP 端到端通过。
- SQLite/PostgreSQL 均可从空库启动并读取迁移后的 Go 数据。

### 正确性

- P0 API 契约 100% 通过。
- 配置继承和 public agent 访问矩阵 100% 通过。
- 同一 session 并发不会交叉、乱序或重复写 tool result。
- usage、quota、API Key scope 与 Go 基线一致。
- 迁移前后核心表行数、主键和抽样内容一致。

### 安全

- 无跨用户/跨 Agent IDOR。
- 文件工具不能越出 workspace。
- `exec` 不可绕过 Docker 在 Java 主机执行。
- SSRF、命令注入、ZIP 路径穿越和环境变量泄漏测试通过。
- 日志不包含 Provider key、API Key 和 Authorization header。

### 性能

- Java 平台自身导致的首包 P95 劣化不超过 Go 基线 20%。
- 100 个并发 SSE 会话下无线程池耗尽、连接泄漏和事件乱序。
- 运行取消后，模型连接和 Docker 任务在规定超时内释放。
- SQLite 明确并发上限；PostgreSQL 满足目标并发。

## 12. 后续版本预留

### V1.1

- ClawHub/GitHub skill 搜索安装。
- Plugin JSON-RPC 和 Hook。
- 完整 Cron 失败策略。
- E2B、BoxLite。
- Team chat、Subagent、Goal continuation、Heartbeat。
- 更多 Web search/image generation/TTS provider。

### V2

- Telegram、Discord、Slack、飞书、LINE、微信。
- Redis Stream、Channel lease、多 Pod。
- S3 workspace/skill 分布式同步。
- Project Runtime、Preview 和端口代理。
- Push/Webhook、完整评测、计费和云平台运维。

## 13. V1 完成定义

1. 现有 FastClaw 前端在 Java 后端上完成全部 V1 核心流程。
2. V1 API、SSE、数据结构和权限行为达到契约要求。
3. Agent 稳定执行多轮模型/工具循环，命令执行具备 Docker 安全边界。
4. Go 数据可迁移，并完成一次完整回滚演练。
5. SQLite 和 PostgreSQL 均通过自动化测试。
6. 监控、日志、部署、升级、备份和回滚文档齐全。
7. 延期能力均有稳定的 capability 表达，不影响现有前端运行。

V1 上线后，Go 实现保留只读归档及一个发布周期的紧急回切能力，再进入 V1.1/V2。
