# OpenAgent Java V3 记忆与上下文实施方案

> 版本：V3.0 方案稿  
> 编制日期：2026-07-17  
> 前置版本：V2 完成态（ReAct 多轮工具循环 + 内置工具，见 [OPENAGENT_JAVA_V2_PLAN.md](OPENAGENT_JAVA_V2_PLAN.md)）  
> 关联文档：[OPENAGENT_JAVA_REFACTORING_PLAN.md](OPENAGENT_JAVA_REFACTORING_PLAN.md)、[OPENAGENT_JAVA_V1_PLAN.md](OPENAGENT_JAVA_V1_PLAN.md)  
> 版本切分说明：经 2026-07-17 讨论，V3 严格按"版本粒度小步快跑"切分——**只做记忆与上下文**（预估 2-4 个有效开发日）；Skill/MCP 归 V4，Docker Sandbox/exec 归 V5

## 1. 版本定位

V3 解决当前 Agent 的两个真实痛点：

1. **长会话爆上下文**：V2 的 `ReActAgentKernel` 每轮把全量历史发给模型，长会话 + 多轮工具结果会快速耗尽上下文窗口，模型报错或质量崩塌；
2. **没有跨会话记忆**：Agent 每次启动都是"失忆"状态，用户偏好、历史决策、重要事实无法沉淀。

V3 增加的核心闭环：

```text
每轮模型调用前
  -> 估算上下文 token
  -> 超过阈值先裁剪旧工具结果
  -> 仍超阈值则调用模型总结旧消息
  -> 完整历史落盘 memory/logs 后可回溯
运行中
  -> MEMORY.md / USER.md 注入 system 上下文
  -> 模型可通过 memory_search 检索记忆
  -> 记忆写入前做安全扫描
```

V3 的判断标准：超长会话不丢 tool call/result 配对、不破坏模型协议、可确定地完成；MEMORY.md/USER.md 可注入、可检索、可更新，且内容按用户隔离。

### 1.1 双参考项目原则

沿用 V2 方案 1.1–1.4 的强制实施流程，不再重复全文，仅明确本版本的参考定位：

| 参考项目 | 路径 | 作用 |
|---|---|---|
| FastClaw | D:\resources\code\fastclaw | compaction/memory/context 行为基线 |
| ragent | D:\resources\code\ragent | Java 分层、配置、日志风格基线 |

每个里程碑必须执行：参考定位 -> 行为清单 -> Java 设计 -> 先测协议 -> 差异验证 -> 真实模型验证 -> 评审记录。有意偏离参考实现时，必须记录原因、风险和测试证据。

### 1.2 FastClaw 行为基线（已对照源码核实，2026-07-17）

| 能力 | FastClaw 参考文件 | 必须保留的行为 |
|---|---|---|
| 上下文压缩 | internal/agent/compaction.go（225 行） | 阈值触发、两段式 prune->summarize、配对保护、历史落盘 |
| 记忆文件 | internal/agent/memory.go（433 行） | MEMORY.md/USER.md 按 chatter 隔离、写入扫描、自动提取 |
| 上下文注入 | internal/agent/context.go（269 行） | BuildSystemPromptAs 的注入位置与 per-chatter 读取规则 |
| 测试基线 | compaction_test.go、memory_test.go、context_chatbot_test.go | 相同风险覆盖不以语言不同为由省略 |

从 compaction.go 提取的必测行为：

1. token 估算为 `chars/4`（content + tool call name/arguments 均计入），不引入 tokenizer 依赖；
2. 默认阈值 `DefaultTokenThreshold = 80000`，超过才触发压缩；
3. Step 1 裁剪：仅处理最近 20 条（`PruneTurnAge = 20`）以外的消息，把其中 content 超过 200 字符的 tool 消息替换为占位符 `[Result truncated - see memory logs]`，**保留 role、ToolCallID、Name**——配对关系不断裂；
4. Step 2 总结：裁剪后仍超阈值时，调用模型把旧消息总结为一条 `[Conversation Summary]` user 消息，拼接近期尾部；总结调用参数为 maxTokens=2048、temperature=0.3、固定 system prompt（"You are a conversation summarizer..."）；
5. 尾部切割必须经 `safeCompactionCutoff` 修正：**切割后第一条消息不得是 role=tool**（否则 OpenAI 兼容 API 直接 400："Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"）；
6. 任何修改前先把完整历史写入 `{workspace}/memory/logs/history_yyyyMMdd_HHmmss.jsonl`；写日志失败只告警不阻断；
7. 总结调用失败时降级使用裁剪后的消息（不因总结失败让运行失败）。

从 memory.go 提取的必测行为：

1. MEMORY.md/USER.md 是 **per-chatter** 数据：store 模式下走 Exact 路径读取，新访客必须看到空记忆，**绝不回退泄漏 agent owner 的副本**；
2. 写入 MEMORY.md/USER.md 前做安全威胁扫描（`SaveMemoryWithScan`/`SaveUserFile`）：检出威胁只告警、仍写入（避免数据丢失）；
3. `AutoPersistMemory` 每 N 轮用 LLM 从最近 20 条消息提取事实，输出 JSON `{"memory_facts": [...], "user_notes": [...]}`，解析前必须 `stripJSONFence`（模型常包裹 ```json 围栏）；提取结果以 `## Auto-persisted: <时间>` 段落追加；
4. LLM 提取失败、JSON 解析失败均 WARN 告警后跳过，不影响当前运行。

从 context.go 提取的注入规则：

1. USER.md 与长期记忆按 chatter 读取（BuildSystemPromptAs），SOUL/IDENTITY 等身份文件读 owner 副本；
2. 当前 OpenAgent 为本地单用户模式，chatter 即 owner，V3 只需保证数据模型预留 userId 边界、注入位置在 system prompt 内。

## 2. V3 范围

### 2.1 必须交付

#### 上下文压缩（M1）

- token 估算器：对齐 `EstimateTokens` 的 chars/4 规则；
- 阈值触发压缩：默认 80000，可配置；
- 两段式：先裁剪旧工具结果（占位符替换、配对保留），仍超阈值再模型总结；
- 尾部切割保护：切割点跳过前导 tool 消息（safeCompactionCutoff 语义）；
- 压缩前完整历史落盘 `{agentWorkspace}/memory/logs/history_*.jsonl`；
- 总结调用失败降级为仅裁剪，不导致运行失败；
- 压缩事件记录日志并可观测（见 4.4，wire 协议处理见 5.3）。

#### 记忆文件与检索（M2）

- Agent 级 workspace 下的 `MEMORY.md`、`USER.md` 读写服务（落位见 4.2）；
- system prompt 注入：MEMORY.md、USER.md 内容随每轮请求注入；
- 写入前安全扫描：检出威胁 WARN 告警但仍写入（对齐 fastclaw 语义）；
- `memory_search` 内置工具：对 MEMORY.md/USER.md/HISTORY.md 做文本检索，默认启用、只读风险级；
- 自动记忆提取（AutoPersistMemory 语义）：每 N 轮运行结束后用 LLM 提取事实追加到 MEMORY.md/USER.md，配置开关控制，失败不阻断运行；
- 数据模型预留 userId（当前固定 `local-user`），为未来 per-chatter 隔离留口。

### 2.2 明确不交付

- heartbeat 驱动的 `ReviewAndUpdateMemory` 关键词整理（依赖 heartbeat 机制，延期）；
- 向量检索、embedding、RAG、知识库；
- Skill、MCP（V4）；Docker Sandbox、exec（V5）；
- Subagent、goal continuation、steer 增强；
- 认证与多用户；per-chatter 隔离只做数据模型预留，不做多用户读取路径；
- 前端记忆管理页面（如前端已有入口则对齐其 API，否则只提供后端能力，见 5.2）。

## 3. 核心用例

### 3.1 长会话不崩

用户与 Agent 进行 50+ 轮含工具调用的对话：

1. 某轮调用前估算 token 超过阈值；
2. 旧工具结果被替换为占位符，仍超阈值则旧消息被总结；
3. 模型请求协议始终合法（无孤立 tool 消息）；
4. 完整历史可在 `memory/logs/` 中回溯；
5. 会话历史页面展示不受影响（压缩只作用于发给模型的上下文，**不改写 session_messages 持久化历史**——见 4.1 关键决策）。

### 3.2 跨会话记忆

1. 用户在会话 A 中告知"我偏好简洁的回答"；
2. 自动提取（或模型经工具）写入 USER.md；
3. 用户开启会话 B，system prompt 注入 USER.md，Agent 回答风格保持简洁；
4. 用户可通过 `memory_search` 让 Agent 检索"我之前说过什么偏好"。

### 3.3 记忆安全

1. 某段待写入记忆的内容命中安全扫描规则；
2. 后端 WARN 记录威胁类型与上下文摘要；
3. 内容仍写入（避免数据丢失），日志不含完整敏感内容。

## 4. 设计

### 4.1 关键决策：压缩只作用于模型上下文，不改写持久化历史

fastclaw 的 `CompactMessages` 作用于发给 provider 的消息列表，`session_messages` 中的完整历史不变。OpenAgent 保持同一语义：

- `session_messages` / `session_events` 始终是完整事实来源，前端历史、重放、审计不受影响；
- 压缩发生在 `PersistedConversation.buildRequest(...)` 内——在内存消息列表的**请求副本**上执行，不动内存主列表之外的状态；压缩后的请求副本用于当次及后续模型调用（内存主列表同步替换，与 fastclaw 行为一致：压缩对本次运行后续轮次生效）；
- 由此 `ReActAgentKernel` **零改动**：压缩对 Kernel 透明，这是 V3 侵入面最小的接入方式。

### 4.2 模块与落位

| 能力 | 落位 | 说明 |
|---|---|---|
| token 估算 + 两段式压缩 | `agent-core`：`ai.openagent.agent.context` 包 | 纯函数式实现（估算、裁剪、切割保护、提示词构造），无 Spring 依赖，可单测；总结调用通过 infra-ai `LLMService` 端口 |
| 压缩接入 | `bootstrap/agentrun/PersistedConversationFactory` | `buildRequest` 前触发 `ContextCompactor`；配置经 `@ConfigurationProperties` 注入 |
| 记忆读写 | `bootstrap`：`ai.openagent.bootstrap.memory` 域 | `MemoryService` 接口 + 文件系统实现（V3 单机模式）；接口预留 DB 实现位 |
| 记忆注入 | `PersistedConversationFactory.open` | 装载 system prompt 时拼接 MEMORY/USER 段落（位置对齐 context.go 注入规则） |
| memory_search | `bootstrap/tool/adapter/MemorySearchTool` + `ToolCatalog` 注册 | 沿用 V2 工具承载决策（V2 方案 20.4 已定案：不恢复 runtime-integration 模块） |
| 自动提取 | `bootstrap/agentrun`：运行结束钩子 | `AgentRunCoordinator` 完成回调中按开关与轮次计数触发 |

记忆文件位置（对齐 fastclaw 布局，agent 级而非 session 级）：

```text
{workspaceRoot}/{agentId}/
├─ MEMORY.md
├─ USER.md
├─ HISTORY.md
├─ memory/logs/history_yyyyMMdd_HHmmss.jsonl
└─ sessions/{sessionId}/     # 现有会话 workspace 不变
```

### 4.3 配置项

```yaml
openagent:
  agent:
    context-token-threshold: ${OPENAGENT_CONTEXT_TOKEN_THRESHOLD:80000}
    context-prune-turn-age: ${OPENAGENT_CONTEXT_PRUNE_TURN_AGE:20}
    context-summary-max-tokens: ${OPENAGENT_CONTEXT_SUMMARY_MAX_TOKENS:2048}
  memory:
    enabled: ${OPENAGENT_MEMORY_ENABLED:true}
    auto-persist-enabled: ${OPENAGENT_MEMORY_AUTO_PERSIST:true}
    auto-persist-interval: ${OPENAGENT_MEMORY_AUTO_PERSIST_INTERVAL:5}
    max-file-chars: ${OPENAGENT_MEMORY_MAX_FILE_CHARS:32768}
```

全部使用 `@ConfigurationProperties`；`auto-persist-interval` 的语义（每 N 次用户消息触发一次提取）实施时先对照 fastclaw loop.go 的 autoPersist gate 确认，存在差异则记录为有意偏离。

### 4.4 可观测性

- 压缩触发：INFO 日志（tokens_before、tokens_after、message_count、runId/sessionId）；
- 历史落盘路径：INFO；写盘失败 WARN 不阻断；
- 总结调用失败降级：WARN；
- 记忆写入扫描命中：WARN（威胁类型 + 摘要，不含完整内容）；
- 自动提取成功/失败：INFO/WARN；
- 指标（Micrometer）：`openagent.context.compactions`、`openagent.memory.autopersist`（成功/失败分别计数）。

### 4.5 压缩事件与 wire 协议

压缩不新增 FastClaw 基线事件类型。处理选项：

- 默认：仅日志与指标，不发 SSE 事件（最小侵入）；
- 可选：自增 `context_compacted` 事件（type 不在 fastclaw 基线内），须按 V2 方案 20.2 问题 3 的规则声明为 OpenAgent 自增事件并验证前端静默忽略无异常。

实施时二选一并记录决策；默认倾向只记日志。

## 5. API 与前端

### 5.1 新增 API（最小集）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/agents/{agentId}/memory` | 读取 MEMORY.md、USER.md 内容 |
| PUT | `/api/agents/{agentId}/memory` | 更新 MEMORY.md、USER.md（写入前扫描） |

### 5.2 前端兼容核查

实施 M2 前必须先 grep `frontend/src/lib/api.ts` 确认前端是否已有记忆文件消费路径：

- 若已有（fastclaw 前端 customize/files 页可能消费 agent 文件接口），API 路径与返回形状**以前端现有定义为准**，上表路径相应调整；
- 若没有，上表为后端能力接口，V3 不做页面。

### 5.3 协议兼容红线

- 压缩后的模型请求必须通过现有 `OpenAiCompatibleLLMService` 的孤立 tool_calls 剥离检查——safeCompactionCutoff + 占位符保留 ToolCallID 已保证配对，测试必须双重验证；
- 不改动现有 SSE 事件类型与字段；
- `memory_search` 的 tool_call/tool_result 走现有事件管线，前端无需修改即可展示。

## 6. 数据库

V3 **默认无 schema 变更**：记忆走文件系统，压缩不产生持久化记录。

待决策（见 8.3）：若 M2 评审认为记忆应入库（为未来 per-chatter 多用户做准备），则增量新增 `agent_memory` 表（agent_id、user_id、filename、content、updated_at），只允许新增、不动既有表。该决策不影响 M1。

## 7. 里程碑

### M1：上下文压缩（1.5-2 天）

先逐段对照 fastclaw `compaction.go` 与 `compaction_test.go`，列出触发、裁剪、切割保护、降级行为清单。

交付：

- `agent-core`：`TokenEstimator`、`ContextCompactor`（两段式、safeCompactionCutoff、总结提示词构造、失败降级）；
- 历史落盘（JSONL，时间戳命名）；
- `PersistedConversation.buildRequest` 接入压缩；
- 配置属性与边界校验；
- 单测先行：
  - 低于阈值不触发；
  - 裁剪只替换 20 条之前的超长 tool 消息且保留配对字段；
  - 切割点落在 tool 消息上时自动前移（对齐 safeCompactionCutoff 单测）；
  - 总结失败降级为仅裁剪；
  - 含多轮 tool call/result 的长历史压缩后无孤立 tool 消息（协议红线）；
  - chars/4 估算与 fastclaw 用例结果一致。

退出条件：构造 100+ 条含工具消息的会话，压缩后模型请求合法且 token 低于阈值；`ToolLoopFlowTest` 等既有测试不回归。

### M2：记忆文件与 memory_search（1.5-2 天）

先逐段对照 fastclaw `memory.go`、`memory_test.go`、`context.go` 注入规则，并核查前端 `api.ts` 现有记忆/文件接口。

交付：

- `MemoryService`（读写 MEMORY.md/USER.md/HISTORY.md、大小上限、安全扫描、userId 预留）；
- system prompt 注入 MEMORY/USER 段落；
- `memory_search` 工具（文本检索、结果截断、默认启用）；
- 自动记忆提取（开关 + 间隔，stripJSONFence 解析，失败 WARN 跳过）；
- 记忆读写 API（5.1，路径以前端核查结果为准）；
- 单测/集成测试：
  - 注入内容出现在模型请求 system 消息中；
  - 写入扫描命中仍写入且 WARN；
  - memory_search 命中与无命中、大小超限截断；
  - 自动提取解析 ```json 围栏包裹的响应（对齐 stripJSONFence 用例）；
  - 自动提取失败不影响运行终态与事件序列。

退出条件：用例 3.2 端到端通过——会话 A 写入偏好，会话 B 的模型请求中可见 USER.md 内容；全量 `mvnw verify` 全绿；真实 kimi-k2.5 smoke 通过。

## 8. 测试与门禁

### 8.1 发布门禁

- `mvnw verify` 全部通过（含 agent-core 新增单测）；
- 协议红线测试（无孤立 tool 消息）必须通过；
- 既有聊天、工具循环、断线续跑回归通过；
- 真实 kimi-k2.5 smoke：长会话压缩场景 + 记忆注入场景；
- 日志扫描确认无完整敏感记忆内容泄漏。

### 8.2 评审衔接（沿用 V2 第 20 章体例）

实施记录必须包含：

- 实际阅读的 FastClaw/ragent 文件清单；
- 有意偏离登记：
  - fastclaw 总结时跳过非 `OriginUser` 消息，OpenAgent 的 `ModelMessage` 暂无 origin 字段——V3 对全部角色生成总结文本，记录为有意偏离，待 origin 字段引入后对齐；
  - `auto-persist-interval` 默认值与 fastclaw gate 的差异（实施时确认后登记）；
  - 记忆落文件系统而非 DB（fastclaw 多租户模式走 store）——本地单用户模式的合理收缩；
- 待用户决策清单：
  - `context_compacted` 是否发自增 SSE 事件（默认不发，见 4.5）；
  - 记忆是否入库（默认 FS，见第 6 章）；
  - 总结调用是否复用当前对话模型（默认复用；独立配置便宜模型延期）。

## 9. 验收标准

1. 超过阈值的长会话能确定完成，模型请求始终合法，无 400 协议错误；
2. 压缩不破坏 tool call/result 配对，不重写 `session_messages` 历史；
3. 压缩前完整历史已落盘 `memory/logs/`；
4. MEMORY.md/USER.md 注入 system prompt，新内容下一轮即生效；
5. `memory_search` 可被模型自主调用并返回真实检索结果；
6. 自动提取开启时，事实按间隔追加到记忆文件；关闭时零副作用；
7. 记忆写入扫描命中产生 WARN 日志但不丢数据；
8. 既有 V2 功能全部不回归。

## 10. 后续版本预留

- **V4**：Skill 加载（SKILL.md、bundled/本地、作用域覆盖）+ MCP 客户端（stdio、HTTP/Streamable HTTP、动态工具注册）；
- **V5**：Docker Sandbox + exec 工具（SandboxBackend 端口、workspace hydrate/sync、默认拒绝策略）；
- 再往后：认证与多用户（per-chatter 记忆隔离随之激活 DB 存储路径）、heartbeat 与 `ReviewAndUpdateMemory`、向量记忆检索、外部渠道、Cron、Subagent——对齐总方案阶段 3-5。

在 V3 完成前不提前引入上述能力，优先保证记忆与上下文这两个基础语义稳定、可测、可演示。
