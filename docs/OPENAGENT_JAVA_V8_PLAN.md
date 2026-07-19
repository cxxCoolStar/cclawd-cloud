# OpenAgent Java V8 计划：Fallback Chain + 会话级任务队列 + Agent CRUD/Onboard

- 版本：V8（方案稿，编制于 2026-07-18）
- 前置版本：V7（配置体系）
- 关联文档：OPENAGENT_JAVA_REFACTORING_PLAN.md、V1–V7 计划

## 1. 目标与背景

对照 fastclaw 核心能力做差距补全，本版本拿下三块"机制已在、只欠填肉"的功能，让三条简历级能力完整成立：

1. **Provider Fallback Chain**：`WebSearchChain` 的 fallback 机制已实现但链上只有 SearxNG 一个 provider，形同虚设；LLM 侧无主备切换。补齐第二个 web search provider + LLM fallback，使"主 Provider 限流/超时/失败自动切换备用"真实成立。
2. **会话级 FIFO 任务队列**：当前同一会话并发请求被 `AgentRunCoordinator.activeRuns` 直接 409 拒绝。fastclaw 语义是 per-chat FIFO 排队 + 全局并发信号量（`internal/taskqueue`），改为排队后"Gateway → TaskQueue → Agent 执行 → SSE 返回"链路完整。
3. **Agent 创建/删除 API + Onboard 后端**：前端 agents 页与 onboard 向导页已备好，后端 POST/DELETE /api/agents 与 /api/onboard 缺失（404）。

判断标准：web search 在主 provider 故障时自动切换备用并有日志；同一会话连发两条消息，第二条排队执行而非 409；前端可创建/删除 agent；onboard 向导可走通。

## 2. 关键摸底结论（开工前已核实）

- WebSearch 现状：`bootstrap/.../tool/websearch/`：`WebSearchProvider` 接口 + `SearxNgWebSearchProvider`（唯一实现）+ `WebSearchChain`（`:39-62`，按 order 排序、retriable 失败回退、非 retriable 快速失败）+ `WebFetchTool`。chain 已消费 `List<WebSearchProvider>`，**新增 provider 只需再加一个 Spring Bean，chain 零改动**。
- LLM 现状：`infra-ai/.../OpenAiCompatibleLLMService` 是 `LLMService` 唯一实现；`LLMServiceConfiguration` 按 agent model → `ProviderRecord` 解析。`providers` 表结构已支持多行。
- 队列现状：`AgentRunCoordinator.java:54,93` 用内存 `activeRuns` Set，同会话并发 → 409。fastclaw `internal/taskqueue/queue.go` 是纯内存 per-chat FIFO + 全局信号量，无持久化——对齐该语义即可，不引入 DB 队列。
- SSE 语义兼容：客户端已支持 `Last-Event-ID`/`?since=N` 重放，排队期间 SSE 连接可先挂起等事件（首事件延迟到达不破坏契约）。
- Agent CRUD 现状：`AgentController` 仅 GET 列表/详情/config + PUT；agent 由 `DataSeeder` 种子产生。`agents` 表 PK id，`sessions`/`agent_runs`/`agent_tools` 等以 agent_id 关联。
- Onboard 现状：`frontend/src/app/onboard/page.tsx` + `frontend/src/lib/api.ts:416` 调 `/api/onboard`；`application.yml:30` 有 `registration-open:false` 占位。**实施第一步必须先读前端这两处确认请求/响应契约**。

## 3. 设计

### 3.1 M1：Provider Fallback Chain

- **Tavily WebSearch provider**：新增 `TavilyWebSearchProvider implements WebSearchProvider`（POST `https://api.tavily.com/search`，`api_key` + query/max_results，解析 `results[{title,url,content}]`）。配置经 properties：`openagent.tools.web-search.tavily.api-key/enabled/priority`；未配 key 时 Bean 不注册（`@ConditionalOnProperty`），行为与现状一致。
- **provider 排序**：给 `WebSearchProvider` 接口加 `order()`（或经配置注入优先级），SearxNG 默认 0、Tavily 默认 10，可配置。失败分类沿用现有 retriable 语义（429/5xx/IO 超时 = retriable → 回退；4xx 参数错误 = 快速失败）。
- **LLM fallback**：新增 `FallbackLLMService` 装饰器：主 provider 调用在**首个 streamed delta 到达前**遇到 retriable 错误（429/5xx/connect timeout）时，切换到备用 provider 重试；delta 已开始流出后不再切换（避免输出拼接）。备用 provider 经 properties 配置（`openagent.llm.fallback.base-url/api-key/model`），未配置时装饰器直通。切换打 WARN 日志（含原因与 provider 标识）。
- **明确不做**：`/api/tools` 的 PUT provider 配置化（维持 V7 的 400 占位，留待配置体系多用户化后一并做）；image gen/TTS。

### 3.2 M2：会话级 FIFO 任务队列

- `AgentRunCoordinator` 改造：
  - 新增 per-session FIFO：`ConcurrentHashMap<sessionKey, ArrayDeque<QueuedRun>>` + 每会话一个"运行中"标志。提交时入队并立即返回（用户消息在提交时落库，保证顺序与可见性）；队首 run 执行完（终态回调）触发同会话下一个出队。
  - 全局并发仍由 `chatTurnExecutor` 有界线程池承载（fastclaw 全局信号量等价物）。
  - **排队等待超时**：默认 60s（`openagent.chat.queue-wait-timeout` 可配），超时移除队列并返回 429/超时业务错误；不再返回 409。
  - SSE 断连不取消排队/运行中的 run（与现状 run 生命周期一致）。
  - `activeRuns` 保留但语义改为"运行中"登记（供状态页/防重）。
- 跨会话并发不受队列限制（不同 session 并行执行，仅受线程池约束）。

### 3.3 M3：Agent 创建/删除 + Onboard

- `POST /api/agents`：body `{name, description?, model?, systemPrompt?}` → 201 + agent 对象；name 必填校验；model 缺省回落 `ModelSettings.name()`。
- `DELETE /api/agents/{id}`：级联清理 `sessions`（+`session_events`/`session_messages`）、`agent_runs`、`agent_tools`、`configs` 表中 `skills.agentEntries.{id}` 键；**种子默认 agent 拒绝删除**（400）；workspace 目录保留不删（对齐 fastclaw 软语义，避免误删产物）。
- `POST /api/onboard`：**先读 `frontend/src/app/onboard/page.tsx` 与 `frontend/src/lib/api.ts` 确认契约再实现**；预期语义 = 首次初始化（设置模型/provider key + 创建首个 agent 或标记初始化完成）。单机单用户模式下不与认证耦合（认证 V9 才来）。
- 前端契约核对：create/delete 的请求形状与列表刷新逻辑以 `frontend/src/lib/api.ts` 为准。

## 4. 里程碑

- **M1（约 0.5–1 天）**：Tavily provider → chain 排序 → LLM fallback 装饰器 → 测试（Tavily 解析、retriable 回退顺序、非 retriable 快速失败、LLM 首 delta 前切换/后不切换、未配置时直通）。
- **M2（约 1 天）**：AgentRunCoordinator 队列化 → 测试（同会话两条消息串行且顺序正确、不同会话并行、排队超时、run 终态触发出队、回归现有 ChatFlow 测试——注意现有 409 语义的测试需改为排队语义）。
- **M3（约 0.5–1 天）**：POST/DELETE /api/agents → /api/onboard → 测试（创建字段校验、级联删除、默认 agent 拒删、onboard 契约形状）。

## 5. 发布门禁

- `JAVA_HOME='D:\software\Java\java17' ./mvnw verify` 全绿；
- 前端契约逐字段核对（agents create/delete、onboard）；
- 手动 smoke：同会话连发两条消息观察第二条排队执行；停掉 SearxNG 观察 web search 自动切换 Tavily（若已配 key）。

## 6. 明确不交付

- `/api/tools` provider 配置化（PUT 维持 400 占位）
- image gen / TTS provider
- 多用户、RBAC、API Key、配置继承链 → V9
- IM 渠道（含 chat_id 维度 session 隔离）→ V10
- 队列持久化 / 跨实例队列（单机内存语义，对齐 fastclaw taskqueue）

## 7. 实施记录

实施完成于 2026-07-19，未提交 commit。`JAVA_HOME='D:\software\Java\java17' ./mvnw verify` 全绿（170 测试，0 失败，1 skipped 为既有 Docker 环境跳过项）。

### M1：Provider Fallback Chain

- 既有产出（开工前已在工作区）：`TavilyWebSearchProvider`、`FallbackLLMService`、`LlmFallbackProperties`，及 `WebSearchProperties`（order/tavily-api-key/tavily-enabled）、`LLMServiceConfiguration`（外包 Fallback 装饰器）、`application.yml`（`openagent.llm.fallback.*`、`openagent.tools.web-search.*`）改动。
- 本次补齐：
  - 新增 `infra-ai/.../FallbackLLMServiceTest`（8 例）：首 delta 前 HTTP 500/429/IO 失败切换备用（校验备用请求的 apiBase/apiKey/model）、delta 已流出不切换、HTTP 400 快速失败、未配置直通、备用 model 为空沿用主模型、主成功不触碰备用。测试发现 `RemoteException` 三参构造不接受 null errorCode，按 `OpenAiCompatibleLLMService` 同款用 `BaseErrorCode.MODEL_INVOKE_ERROR`。
  - 新增 `TavilyWebSearchProviderTest`（8 例）：请求体形状（api_key/query/max_results）、results 解析与 count 截断、429/500/不可达 retriable、401 与缺 key/禁用快速失败。
  - 修复 M1 遗留 Spring 装配 bug：`TavilyWebSearchProvider` 双构造器导致 `No default constructor found`（此前小范围单测未起 Spring 上下文未暴露），主构造器加 `@Autowired`。

### M2：会话级 FIFO 任务队列

- `AgentRunCoordinator` 重写：同会话 409 改为 per-session FIFO（`ConcurrentHashMap<key, SessionQueue>` + 队列锁内"判空停派/入队开派"互斥；空队列常驻 map，规模受会话数约束，换取无竞态）。提交即落库 user 消息与 run 记录（CREATED → 派出时 RUNNING → 终态）；终态回调触发同会话下一个出队；跨会话并行，全局并发仍由 `chatTurnExecutor` 承载。
- 排队超时：单 daemon 调度线程按 `openagent.chat.queue-wait-timeout`（默认 60s，新增 `ChatProperties.queueWaitTimeout`，application.yml 已补 `OPENAGENT_CHAT_QUEUE_WAIT_TIMEOUT`）移除排队 run，标记 FAILED/`QUEUE_WAIT_TIMEOUT`，补发 error/done 瞬时事件收敛前端，future 以 `SERVICE_TIMEOUT_ERROR` 异常完成；`GlobalExceptionHandler` 新增该码 → 429 映射。
- 线程池拒绝（RejectedExecution）只失败当前 run 并继续尝试后续排队 run。
- 测试：`ToolLoopFlowTest` 原 409 测试改为 `concurrentRunOnSameSessionIsQueuedInOrder`（排队 + 提交即落库顺序 user/user/assistant/assistant + 双 run COMPLETED）+ 新增 `runsOnDifferentSessionsExecuteInParallel`（脚本模型 latch 收窄为只阻塞含 "hold" 的消息）；新增 `AgentRunQueueTimeoutFlowTest`（1s 超时配置，验证 429 错误码、FAILED/QUEUE_WAIT_TIMEOUT 落库、首 run 不受影响）。ChatFlow/AutoPersistMemory/ContextCompaction 回归绿。

### M3：Agent 创建/删除 + Onboard

- 契约先读前端确认（`frontend/src/lib/api.ts:1343/1467/416`、`agents/page.tsx:209-276`、`onboard/page.tsx`）：POST 响应读 `resp.agent.id`、DELETE 读 `body.ok/error`、onboard 读 body `ok/error` 而非 HTTP 状态。
- `POST /api/agents`：201 + `{agent}`；`AgentCreateRequest`（name @NotBlank）；id = `agt_` + 8 位随机 hex；model/systemPrompt 缺省回落 ModelSettings；按 ToolCatalog 补种内置工具默认配置（与 DataSeeder 同源）。
- `DELETE /api/agents/{id}`：`AgentRepository.deleteCascade` 清 session_events/messages/sessions/tool_executions/agent_runs/agent_tools/agent_mcp_servers + agents 行；service 层 `@Transactional` 并清 `skills.agentEntries.{id}` 配置键；默认 agent（"default"）拒删 400，未知 404；workspace 目录保留。
- `POST /api/onboard`：`OnboardController` + `OnboardService`（拆层是因 CodeStyleArchitectureTest 禁止 controller 依赖 persistence；OnboardRequest 移到 `controller.request` 子包同理）。provider+apiKey 非空才写默认供应商（apiBase 缺省保留现值，apiType/authType 忽略——仅 OpenAI 兼容协议），model 同步默认 agent；agentName 非空且非 "default" 时创建首个业务 agent；admin/sandbox 字段接收但忽略（V9 认证 / sandbox env 配置）。
- 配套闭环改动：
  - `DataSeeder.seedDefaultProvider`：env 未配置 apiKey 时跳过刷新保留 DB 值（否则 onboard 写入重启即被 env 空值覆盖）；env 已配置维持原"每次启动刷新"语义。
  - `PlatformStatusServiceImpl`：`configured` = env ready || 默认供应商 DB 行有 apiKey（否则 onboard 后 `/api/status` 仍 false，前端 auth-guard 死循环回 onboard）。
- 测试：新增 `AgentLifecycleEndpointsTest`（4 例：创建缺省回落与 name 校验 400、级联删除全量断言 + 默认拒删 + 未知 404、onboard 写供应商 + 建 agent、空 provider 跳过）。

### 遗留与注意

- `/api/status` 的 provider 段（provider/name/apiBase 回显）仍读 ModelSettings（env 派生），DB 写入的 apiBase/model 不回显——仅影响展示，运行时解析走 providers 表。
- onboard 的 anthropic（apiType=anthropic-messages）前端可选但运行时仅 OpenAI 兼容协议，聊天时会失败——多协议留待后续版本。
- 发布门禁中的手动 smoke（同会话连发两条消息、停 SearxNG 观察切换 Tavily）未执行，仅以自动化测试覆盖。
