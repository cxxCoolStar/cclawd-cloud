# OpenAgent

OpenAgent 是一个自托管 AI Agent 平台，包含 Java 后端、Next.js 管理台、工具执行框架、技能系统、MCP 客户端、会话工作区、长期记忆、渠道接入与评测体系。项目默认可在本机以 SQLite 启动，也提供 PostgreSQL + Redis 的本地容器化部署方案。

## 功能概览

- **多 Agent 管理**：支持创建、配置和管理 Agent，按 Agent 维护模型、工具、技能、知识、上下文、会话与使用情况。
- **流式聊天与工具调用**：兼容 OpenAI Chat Completions 风格接口，支持流式输出、标准 tool calls、工具结果回传和多轮 Agent 循环。
- **内置工作区工具**：提供目录列表、文件读取、文件写入、文件编辑、补丁应用、网页抓取、网页搜索、记忆检索、沙箱命令执行等工具。
- **技能系统**：通过 `SKILL.md` 描述可加载技能，支持全局技能目录和 Agent 级技能目录，按需加载完整指令。
- **MCP 客户端**：支持按 Agent 配置 MCP server，通过 stdio 或 HTTP transport 动态发现外部工具。
- **会话工作区**：每个会话拥有隔离目录，文件工具限制在会话工作区内，阻止路径穿越、符号链接逃逸和二进制文件误读。
- **长期记忆与上下文压缩**：支持 `MEMORY.md`、`USER.md`、`HISTORY.md` 注入，自动记忆抽取，以及超过阈值后的上下文裁剪与摘要。
- **渠道运行时**：提供 IM 渠道接入框架、消息收发队列、运行时观测和本地/Redis 两种消息总线模式。
- **身份与管理**：包含注册、登录、会话、API Key、用户管理和平台状态接口。
- **评测体系**：内置 YAML 评测用例、评分器和测试资源，用于验证工具、安全边界、技能、记忆、上下文和多步任务表现。

## 技术栈

- Backend: Java 17, Spring Boot 3.5, MyBatis-Plus, Flyway
- Database: SQLite 默认，支持 PostgreSQL
- Cache / Queue: Redis 可选，用于分布式渠道运行时
- Frontend: Next.js 16, React 19, TypeScript, Tailwind CSS, shadcn-style components
- AI: OpenAI-compatible Chat Completions API
- Sandbox: Docker CLI 可选，用于隔离执行 `exec` 工具

## 项目结构

| 路径 | 说明 |
|---|---|
| `framework` | 通用返回结构、错误码、异常和 Web 基础设施 |
| `infra-ai` | OpenAI-compatible 模型客户端、流式响应和工具调用聚合 |
| `agent-core` | Agent 核心循环、工具端口、上下文压缩和评测基础模型 |
| `bootstrap` | Spring Boot 启动模块、API、持久化、工具适配器、渠道、身份、MCP、沙箱 |
| `frontend` | Web 管理台与聊天界面 |
| `skills` | 内置技能目录 |
| `eval` | 评测用例 |
| `docs` | 设计文档、排障和评测说明 |
| `deploy` | Docker Compose 与 Kubernetes 部署资源 |
| `workspace` | 默认本地工作区目录 |

## 环境要求

- JDK 17
- Node.js 20+
- pnpm
- Docker 可选，仅在启用沙箱执行或本地容器部署时需要

项目包含 Maven Wrapper。默认 Maven 依赖缓存目录可使用仓库内的 `.maven-user`，便于本地隔离。

## 模型配置

至少需要配置模型 API Key，才能正常发起聊天请求：

```powershell
$env:OPENAGENT_MODEL_API_KEY = "your-api-key"
```

如需使用其他 OpenAI-compatible 服务：

```powershell
$env:OPENAGENT_MODEL_PROVIDER = "openai-compatible"
$env:OPENAGENT_MODEL_API_BASE = "https://your-provider.example/v1"
$env:OPENAGENT_MODEL_API_KEY = "your-api-key"
$env:OPENAGENT_MODEL = "your-model"
```

没有 API Key 时，应用和数据库仍可启动，但发送消息会返回清晰的模型配置错误。

## 本地启动

先构建前端，让静态资源进入后端可执行包：

```powershell
Set-Location frontend
pnpm install --frozen-lockfile
pnpm build
Set-Location ..
```

再测试并打包后端：

```powershell
$env:JAVA_HOME = "D:\software\Java\java17"
$env:MAVEN_USER_HOME = "$PWD\.maven-user"
.\mvnw.cmd test
.\mvnw.cmd -pl bootstrap -am package -DskipTests
java -jar bootstrap\target\bootstrap-0.1.0-SNAPSHOT.jar
```

启动后访问 [http://127.0.0.1:18953](http://127.0.0.1:18953)。

常用健康检查：

- [http://127.0.0.1:18953/healthz](http://127.0.0.1:18953/healthz)
- [http://127.0.0.1:18953/api/status](http://127.0.0.1:18953/api/status)
- [http://127.0.0.1:18953/api/me](http://127.0.0.1:18953/api/me)
- [http://127.0.0.1:18953/api/agents](http://127.0.0.1:18953/api/agents)

## 本地容器部署

本地 Compose 会启动 PostgreSQL、Redis 和应用服务：

```powershell
docker compose -f deploy\local\compose.yaml up --build
```

默认应用地址为 [http://127.0.0.1:18956](http://127.0.0.1:18956)。Compose 会从根目录 `.env` 读取模型等配置，并将工作区挂载到 `/data/workspace`。

停止服务：

```powershell
docker compose -f deploy\local\compose.yaml down
```

## 核心配置

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENAGENT_BIND` | `127.0.0.1` | HTTP 监听地址 |
| `OPENAGENT_PORT` | `18953` | HTTP 端口 |
| `OPENAGENT_DATABASE_URL` | `jdbc:sqlite:openagent.db` | 数据库连接 |
| `OPENAGENT_DATABASE_USERNAME` | 空 | 数据库用户名 |
| `OPENAGENT_DATABASE_PASSWORD` | 空 | 数据库密码 |
| `OPENAGENT_DATABASE_POOL_SIZE` | `1` | 数据库连接池大小 |
| `OPENAGENT_REGISTRATION_OPEN` | `false` | 是否开放注册 |
| `OPENAGENT_MODEL_PROVIDER` | `openai` | 模型供应商标识 |
| `OPENAGENT_MODEL_API_BASE` | `https://api.openai.com/v1` | 模型 API Base URL |
| `OPENAGENT_MODEL_API_KEY` | 空 | 模型 API Key |
| `OPENAGENT_MODEL` | `gpt-4.1-mini` | 默认模型 |
| `OPENAGENT_MODEL_TEMPERATURE` | `0.7` | 默认温度 |
| `OPENAGENT_MODEL_MAX_TOKENS` | `2048` | 单次响应 token 上限 |
| `OPENAGENT_SYSTEM_PROMPT` | 空 | 覆盖默认系统提示词 |
| `OPENAGENT_LLM_FALLBACK_BASE_URL` | 空 | 备用模型服务地址 |
| `OPENAGENT_LLM_FALLBACK_API_KEY` | 空 | 备用模型 API Key |
| `OPENAGENT_LLM_FALLBACK_MODEL` | 空 | 备用模型名称 |

## Agent 与工具配置

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENAGENT_AGENT_MAX_TOOL_ITERATIONS` | `8` | 单轮最多工具循环次数 |
| `OPENAGENT_AGENT_RUN_TIMEOUT` | `10m` | 单次 Agent 运行超时 |
| `OPENAGENT_TOOL_TIMEOUT` | `30s` | 单个工具执行超时 |
| `OPENAGENT_TOOL_MAX_RESULT_CHARS` | `65536` | 工具结果最大字符数 |
| `OPENAGENT_WORKSPACE_ROOT` | `./workspace` | 会话工作区根目录 |
| `OPENAGENT_READ_FILE_MAX_BYTES` | `1048576` | 单文件读取大小上限 |
| `OPENAGENT_WEB_FETCH_ENABLED` | `true` | 是否允许网页抓取工具 |
| `OPENAGENT_WEB_FETCH_MAX_BYTES` | `1048576` | 网页抓取响应大小上限 |
| `OPENAGENT_WEB_SEARCH_ORDER` | `tavily,searxng` | 网页搜索供应商顺序 |
| `OPENAGENT_WEB_SEARCH_TAVILY_API_KEY` | 空 | Tavily API Key |
| `OPENAGENT_WEB_SEARCH_TAVILY_ENABLED` | `true` | 是否启用 Tavily |
| `OPENAGENT_WEB_SEARCH_SEARXNG_ENDPOINT` | 空 | SearXNG 服务地址 |
| `OPENAGENT_SKILLS_DIR` | `./skills` | 全局技能目录 |

内置工具默认状态：

| 工具 | 默认状态 | 说明 |
|---|---|---|
| `list_dir` | 启用 | 列出工作区目录 |
| `read_file` | 启用 | 读取工作区文本文件 |
| `write_file` | 禁用 | 写入工作区文件 |
| `edit_file` | 禁用 | 按精确片段编辑文件 |
| `apply_patch` | 禁用 | 应用多文件补丁 |
| `web_fetch` | 禁用 | 抓取 HTTP/HTTPS 内容，受全局配置约束 |
| `web_search` | 禁用 | 调用配置的网页搜索供应商 |
| `memory_search` | 启用 | 搜索 Agent 记忆文件 |
| `exec` | 禁用 | 在 Docker 沙箱中执行命令 |
| `load_skill` | 启用 | 按名称加载技能完整指令 |

工具是否可用还会受 Agent 级开关影响。文件类工具会被限制在对应会话工作区内。

## 记忆与上下文

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENAGENT_CONTEXT_TOKEN_THRESHOLD` | `80000` | 触发上下文压缩的估算 token 阈值 |
| `OPENAGENT_CONTEXT_PRUNE_TURN_AGE` | `20` | 压缩时保留的最近轮次数 |
| `OPENAGENT_CONTEXT_SUMMARY_MAX_TOKENS` | `2048` | 摘要调用最大 token |
| `OPENAGENT_MEMORY_ENABLED` | `true` | 是否注入记忆文件 |
| `OPENAGENT_MEMORY_AUTO_PERSIST_ENABLED` | `true` | 是否自动抽取记忆 |
| `OPENAGENT_MEMORY_AUTO_PERSIST_INTERVAL` | `5` | 每 N 条用户消息触发一次自动记忆 |
| `OPENAGENT_MEMORY_MAX_FILE_CHARS` | `32768` | 单个记忆文件最大字符数 |
| `OPENAGENT_WORKSPACE_HISTORY_ENABLED` | `true` | 是否记录工作区历史快照 |

## 沙箱执行

`exec` 工具依赖 Docker，并且需要同时打开全局开关和 Agent 工具开关：

```powershell
$env:OPENAGENT_SANDBOX_DOCKER_ENABLED = "true"
```

相关配置：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENAGENT_SANDBOX_IMAGE` | `python:3.12-slim` | 沙箱镜像 |
| `OPENAGENT_SANDBOX_CPUS` | `1` | CPU 限制 |
| `OPENAGENT_SANDBOX_MEMORY` | `512m` | 内存限制 |
| `OPENAGENT_SANDBOX_NETWORK` | `bridge` | 网络模式，可设为 `none` |

命令在容器内执行，Agent 工作区挂载为 `/workspace`。

## 渠道运行时

默认使用本地内存总线：

```powershell
$env:OPENAGENT_CHANNEL_BUS = "local"
```

分布式部署可切换到 Redis：

```powershell
$env:OPENAGENT_CHANNEL_BUS = "redis"
$env:OPENAGENT_REDIS_HOST = "127.0.0.1"
$env:OPENAGENT_REDIS_PORT = "6379"
```

常用配置：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OPENAGENT_CHANNEL_ROLES` | `api,channel-ingress,agent-worker,channel-egress` | 当前实例承担的渠道角色 |
| `OPENAGENT_CHANNEL_REDIS_PREFIX` | `openagent:channel` | Redis key 前缀 |
| `OPENAGENT_CHANNEL_LEASE_TTL` | `30s` | worker 租约 TTL |
| `OPENAGENT_CHANNEL_LEASE_RENEW_INTERVAL` | `10s` | worker 租约续约间隔 |

## 评测

普通单元测试默认排除需要真实模型调用的评测分组：

```powershell
.\mvnw.cmd test
```

如需显式运行评测分组，可按测试配置传入 group 参数：

```powershell
.\mvnw.cmd test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval
```

评测用例位于 `eval/cases` 和 `bootstrap/src/test/resources/eval/cases`，覆盖文件工具、安全边界、网页工具、技能加载、记忆、上下文压缩、多步任务和异常恢复等场景。

## 开发入口

- 前端开发：`cd frontend && pnpm dev`
- 前端构建：`cd frontend && pnpm build`
- 后端测试：`.\mvnw.cmd test`
- 后端打包：`.\mvnw.cmd -pl bootstrap -am package -DskipTests`
- 后端启动：`java -jar bootstrap\target\bootstrap-0.1.0-SNAPSHOT.jar`

更多部署和排障资料可参考 `docs` 与 `deploy/kubernetes`。
