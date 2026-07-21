# OpenAgent Java V10 计划：微信 IM 渠道接入 + per-chatter 会话/记忆隔离

- 版本：V10（方案稿，编制于 2026-07-18）
- 前置版本：V9（多用户体系）
- 关联文档：OPENAGENT_JAVA_REFACTORING_PLAN.md、V1–V9 计划
- 变更说明：应用户要求，首个 IM 渠道由 Telegram 改为**微信**（参照 fastclaw `internal/channels/wechat.go` 的 iLink 长轮询实现，实现的过程中不要再注释中写参考 fastclaw.... 只需要在注释中说明该模块，方法的功能描述）

## 1. 目标与背景

打通"微信消息进来 → 路由到 agent → ReAct 执行 → 回复微信"的完整链路，补上 fastclaw 核心能力中"IM 渠道"这条简历级能力；同时引入 channel/chat_id 维度，让 session 隔离键 `(user_id, agent_id, channel_type, chat_id)` 完整成立——同一个 agent 服务多个微信聊天对象时，会话与记忆按 chatter 相互隔离。

判断标准：微信用户给绑定的公众号/接口发消息，agent 处理并回复；两个不同微信用户对同一 agent 的对话互不可见、记忆互不串扰；Web 端现有行为零回归。

## 2. 关键摸底结论（开工前已核实）

- fastclaw 微信适配器（`internal/channels/wechat.go:43`）：**iLink 长轮询**接入（非 webhook），入站消息投递到 bus；回复直接 HTTPS 调微信侧 API。单 pod 部署无需 Redis 租约（租约只为多副本防重复轮询）。
- fastclaw 消息模型：`bus.InboundMessage`（Channel/AccountID/ChatID/UserID/Text/MessageID/MediaItems）；出站按 `(channel, accountID)` 找适配器直接发送；流式中间态用 edit-in-place（微信侧能力需实测，不支持则降级为最终一次性回复）。
- OpenAgent 现状：`sessions.channel` 列已存在（当前固定 `web`）；V8 已交付会话级 FIFO 队列（天然适配 per-chat 串行）；V9 已交付多用户 + owner 校验。无 bus 抽象——`ChatController → AgentRunCoordinator` 直连。
- 渠道与 agent 的绑定关系、渠道配置页面：前端 `channels/`、`channels-config/` 页面已存在但无后端，**实施第一步必须读前端契约**（`frontend/src/lib/api.ts` 中 channels 相关部分）。
- per-chatter 记忆：fastclaw 按 chatter 分区 MEMORY.md/USER.md；OpenAgent 现状是 `{workspaceRoot}/{agentId}/`（V3 已预留 userId 参数、`agent_memory` 表决策项未落地）。

## 3. 设计

### 3.1 M1：渠道接入内核（微信入站 → agent → 出站）

- **ChannelAdapter 抽象**：`internal/channels` 的 Java 等价物——接口 `ImChannelAdapter { start(); stop(); send(chatId, text); }`，Spring 管理生命周期；`wechat` 为首个实现。
- **微信适配器**：iLink 长轮询循环（对齐 fastclaw wechat.go：拉取 → 解析 → 投递 → 应答/重试退避）；凭证（appid/token 等）经 `configs` 表 `channels.wechat` 键配置 + 管理端点（契约以前端 channels 页面为准），密钥打码回显沿用 V7 语义。
- **入站桥**：微信消息 → 归一化为 `ChannelInboundMessage{channel:"wechat", chatId, chatterId, text, messageId}` → 解析绑定关系（`channel_bindings` 新表：channel+account → agent_id + owner user_id，管理员配置）→ 构造/复用 session（见 3.2）→ 提交 `AgentRunCoordinator`（复用 V8 队列，session key 含 chat_id，同一会话天然串行）。
- **出站桥**：订阅 run 的产出事件，聚合文本（微信无可靠 edit-in-place 则等终态一次性回复；中间态可选"正在思考"占位消息）→ 调微信发送 API。
- **不做 bus/Redis**：单进程内直接方法调用；多副本总线维持"规模到了再做"的既定结论。

### 3.2 M2：per-chatter 会话与记忆隔离

- **session 键扩展**：`sessions.channel` 启用真实值（`web`/`wechat`），新增 `chat_id` 列（migration）；会话唯一键变为 `(user_id, agent_id, channel, chat_id)`；web 端 chat_id 恒空，行为不变。
- **chatter 身份**：微信用户 OpenID → chatter 标识；session 的 user_id 取 **agent owner**（数据归属 owner，对齐 fastclaw），chatter 存于独立列/会话元数据。
- **per-chatter 记忆**：目录扩展为 `{workspaceRoot}/{agentId}/chats/{channel}/{chatId}/MEMORY.md|USER.md`（web 会话维持现状路径不变）；`MemoryService` 加 channel/chatId 参数（V3 预留的 userId 参数语义落地为 chatter 分区）；跨 chatter 不可检索——`MemorySearchTool` 作用域限定当前 chatter。
- **共享配置**：chatter 复用 owner 的模型配置（fastclaw `shareModelConfig` 默认语义）；user-agent 第四级配置继承仍不做。

### 3.3 M3：管理面 + 收尾

- 渠道管理端点：绑定关系 CRUD（哪个微信账号 → 哪个 agent）、渠道连接状态查询（对齐前端 channels 页面契约）。
- `PlatformCapabilities.channels` false → true。
- 审计：渠道消息收发落 `session_events`（复用现有事件流，消息来源标注 channel）。

## 4. 里程碑

- **M1（约 1.5–2 天）**：ChannelAdapter 抽象 → 微信 iLink 轮询适配器 → 入站/出站桥 → 绑定表 + 配置 → 测试（消息解析、绑定路由、回复发送 mock、断线重连退避）。
- **M2（约 1 天）**：sessions migration + chat_id 贯穿 → per-chatter 记忆分区 → 测试（两 chatter 同 agent 会话隔离、记忆互不检索、web 零回归）。
- **M3（约 0.5 天）**：管理端点 + capabilities + 事件标注 → 前端契约核对 → 真机 smoke。

## 5. 发布门禁

- `JAVA_HOME='D:\software\Java\java17' ./mvnw verify` 全绿；
- 越权/越 chatter 负向测试（chatter B 的会话与记忆对 chatter A 不可见）；
- 真机 smoke：微信侧发消息 → agent 回复；两个微信用户交叉验证隔离。

## 6. 明确不交付

- Redis 总线 / 多副本渠道租约（维持单机定位，规模到了再做）
- 其他 IM 渠道（飞书/Discord/Slack/LINE 等，适配器抽象就位后逐个追加）
- 富媒体（图片/语音/文件消息）——首版仅文本
- 微信群聊 @ 路由（fastclaw routeGroup 语义）——首版仅单聊
- 插件 JSON-RPC 子进程（候选 V11）
