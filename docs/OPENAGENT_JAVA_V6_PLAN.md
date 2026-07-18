# OpenAgent Java V6 MCP 模块实施方案

> 版本：V6.0 方案稿  
> 编制日期：2026-07-18  
> 前置版本：V5 完成态（Skill 模块，见 [OPENAGENT_JAVA_V5_PLAN.md](OPENAGENT_JAVA_V5_PLAN.md)）  
> 关联文档：[OPENAGENT_JAVA_REFACTORING_PLAN.md](OPENAGENT_JAVA_REFACTORING_PLAN.md)（6.5 节）

## 1. 版本定位

让 Agent 接入 MCP（Model Context Protocol）工具生态：管理员给 Agent 配置 MCP Server（stdio 子进程或 HTTP），其工具以 `mcp_<server>_<tool>` 命名动态出现在模型的工具列表里，模型可像内置工具一样调用。判断标准：

```text
PUT 配置一个 MCP Server
  -> GET /api/agents/{id}/config 可见 mcpServers
  -> 下一轮对话模型看到 mcp_* 工具
  -> 模型调用 mcp_* 工具，经 MCP 客户端获得真实结果
  -> 结果回传模型完成闭环
```

## 2. FastClaw 行为基线（已核实，2026-07-17）

| 能力 | FastClaw 参考 | 必须保留的行为 |
|---|---|---|
| 客户端管理 | internal/mcp/manager.go | 工具名前缀 `mcp_<sanitizedServerName>_<tool>`（非字母数字下划线化）；prefixed → (server, tool) 路由表 |
| stdio | internal/mcp/stdio.go | 子进程 JSON-RPC |
| HTTP | internal/mcp/http.go | HTTP/Streamable HTTP |
| 配置形状 | frontend api.ts MCPServerConfig | `{type: "http"\|"stdio", url?, headers?, command?, args?, env?}` |

## 3. V6 范围

### 3.1 必须交付

- 官方 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp:0.10.0`）接入：stdio + Streamable HTTP 两种传输；
- `agent_mcp_servers` 表（Flyway V3）：agent_id、name、type、url、headers_json、command、args_json、env_json，PK(agent_id, name)；
- API：
  - `GET /api/agents/{id}/config` → 最小形状 `{mcpServers: {...}}`（前端 MCP 页读取）；
  - `PUT /api/agents/{id}` 接受 `{mcpServers}` 整表替换（前端 MCP 页保存；其他字段本版本忽略并记录）；
- `McpClientManager`：按 (agentId, serverName) 懒连接、工具发现、客户端缓存、失败驱逐重连；
- `McpToolAdapter`：MCP 工具桥接为 AgentTool（描述透传 inputSchema，source=MCP），命名 `mcp_<server>_<tool>`（fastclaw 对齐）；
- `CatalogToolRegistry` 集成：MCP 工具并入 availableTools / requireEnabled（不走 agent_tools 启停表——fastclaw 同款：server 配置即启用）。

### 3.2 明确不交付

- MCP 全局（system 级）server 配置与作用域继承（当前只有 agent 级）；
- SSE（旧式）传输、roots/sampling/elicitation 高级能力、MCP resources/prompts；
- 工具变更监听与 Registry 原子刷新（V6 按调用懒发现，重启后重连）；
- `/api/config` 配置体系、MCP 连接测试/工具列表管理接口；
- stdio server 经 Docker 沙箱执行（安全增强，后续版本）。

## 4. 设计

### 4.1 模块落位

| 能力 | 落位 |
|---|---|
| `McpServerRecord` / `AgentMcpServerRepository` | `bootstrap/persistence/` + Flyway V3 |
| `McpClientManager`（连接生命周期、发现、调用路由） | `bootstrap/mcp/` |
| `McpToolAdapter`（AgentTool 桥接） | `bootstrap/mcp/` |
| 配置 API | `AgentController`（GET config / PUT mcpServers） |
| Registry 集成 | `CatalogToolRegistry` 增加 MCP 工具源 |

### 4.2 工具解析顺序（availableTools）

```text
内置工具：ToolCatalog ∩ 已装配实现 ∩ agent_tools 启用（现状不变）
MCP 工具：agent_mcp_servers 中该 agent 的 server → 懒连接 → tools/list → mcp_* 适配
连接失败的 server：记 WARN 并跳过，不影响其他 server 与内置工具
```

### 4.3 超时与失败

- 连接/发现超时 10s，调用超时沿用工具 execution-timeout；
- callTool 的 isError=true 映射为 ToolResult.failure；
- 连接/调用抛异常：驱逐该 server 缓存客户端（下次调用重连），返回 TOOL_EXECUTION_FAILED。

## 5. 测试

- stdio：Node 脚本模拟 MCP stdio server（initialize/tools/list/tools/call JSON-RPC）；
- HTTP：Node 脚本模拟 Streamable HTTP server（SSE 响应）；
- 单测：前缀命名与清洗、路由、失败驱逐、Registry 集成（MCP 工具并入/连接失败跳过）；
- API 集成测试：PUT/GET config 整表替换；
- 真实验证：配置 stdio server → 真实 kimi-k2.5 自主调用 mcp_* 工具闭环。

## 7. 实施记录（2026-07-18 完成）

### M1：MCP 模块 ✅ 已完成（约 1 天）

- **交付物**：
  - `bootstrap/pom.xml`：`io.modelcontextprotocol.sdk:mcp:0.12.0`（0.10 无 Streamable HTTP；0.14+ 已迁移为 relocation POM）
  - Flyway `V3__agent_mcp_servers.sql` + `AgentMcpServerRecord/Repository`（整表替换）
  - `bootstrap/mcp/McpClientManager.java`（懒连接/缓存/失败驱逐、`mcp_<server>_<tool>` 前缀、工具发现、调用路由）
  - `bootstrap/mcp/Utf8StdioClientTransport.java`（**自研 UTF-8 stdio 传输**，见有意差异）
  - `bootstrap/mcp/McpToolAdapter.java`（AgentTool 桥接，source=MCP）
  - `CatalogToolRegistry`：MCP 工具并入 availableTools/requireEnabled（server 配置即启用，不经 agent_tools）
  - API：`GET /api/agents/{id}/config`、`PUT /api/agents/{id}`（仅 mcpServers 整表替换；缺省不动、{} 清空）
- **参考文件**：fastclaw `internal/mcp/manager.go`（prefixToolName）、`stdio.go`、`http.go`；前端 `api.ts` MCPServerConfig
- **测试**：`McpClientManagerIT`（3 用例：stdio 发现/调用/中文回显、前缀清洗、HTTP 发现/调用——Node 模拟 MCP server ×2）；`AgentConfigEndpointsTest`（3 用例：整表替换/类型校验/404）；全量 `mvnw verify` 120 tests 全绿
- **真实端到端验证（2026-07-18，kimi-k2.5）**：PUT 配置 stdio server → 模型自主调用 `mcp_testserver_echo` → 子进程真实回显 → 闭环完成
- **有意差异**：
  - **SDK StdioClientTransport 以平台默认编码读子进程输出（Windows 中文环境 GBK），非 ASCII 工具结果乱码**——自研 `Utf8StdioClientTransport` 显式 UTF-8 读写，另修复出站竞态（sendMessage 先于 connect 完成时经出站缓冲按序写出）；已加中文回显回归用例；
  - `PUT /api/agents/{id}` 本版本只处理 mcpServers，其他前端字段忽略（V6 方案 3.1 记录）
- **遗留**：MCP 连接失败时 `requireEnabled` 每次重新发现（性能可接受，后续可加缓存）；无全局 server 作用域、无连接测试接口

## 6. 验收

1. PUT 配置后 GET config 可见，形状与前端 MCPServerConfig 一致；✅
2. 模型工具列表出现 mcp_* 工具且描述/schema 正确；✅
3. stdio 与 HTTP server 均可被发现与调用；✅（集成测试 + 真实验证）
4. server 不可达不影响内置工具与其他 server；✅（McpClientManager 记 WARN 跳过）
5. 全量 verify 全绿 + 真实模型闭环通过。✅
