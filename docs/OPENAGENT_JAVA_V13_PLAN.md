# OpenAgent Java V13 计划：Cron/Scheduler 定时任务

- 版本：V13 方案稿
- 日期：2026-07-23
- 前置版本：V12（另一条线推进中，本文仅假设 V12 不破坏现有 AgentRunCoordinator、身份、配置与 channel runtime 契约）
- 关联文档：OPENAGENT_JAVA_REFACTORING_PLAN.md、OPENAGENT_JAVA_V9_PLAN.md、OPENAGENT_JAVA_V11_PLAN.md、migration/M0_BASELINE.md

## 1. 目标与背景

实现Cron/Scheduler 能力，让 agent 从“用户触发聊天”扩展为“按计划主动执行任务”。本版本交付三条闭环：

1. 管理员或用户可以在全局 `/cron` 页面创建、启停、删除定时任务；
2. agent 自身可以通过 `create_cron_job` 工具把用户在聊天中提出的提醒、周期任务、一次性任务写入调度系统；
3. Scheduler 到点后自动提交 agent turn，执行结果进入普通会话历史、事件流与 token usage 统计。

判断标准：用户在聊天里说“5 分钟后提醒我休息”或在 `/cron` 页面创建“每天 9 点生成日报”，任务会出现在 `/agents/{id}/scheduler`，到点只执行一次或按周期执行；禁用/删除立即生效；不同用户互相看不到对方任务；进程重启后任务不会丢失。

## 2. 关键摸底结论

- 前端契约已存在：
  - 全局页 `/cron` 调用 `GET/POST /api/cron`、`PUT/DELETE /api/cron/{id}`；
  - agent 页 `/agents/{id}/scheduler` 调用 `GET /api/agents/{id}/cron`、`PUT/DELETE /api/agents/{id}/cron/{jobId}`；
  - `CronJobInfo` 字段为 `id/name/type/schedule/agentId/channel/chatId/message/enabled/lastRun/nextRun`；
  - `AgentCronJob` 注释已声明其来源包含 agent 自己调用的 `create_cron_job` 工具。
- 后端尚未落地 Cron 域：当前仅有 SSE heartbeat、channel recovery 等内部 scheduler；`PlatformCapabilities.cron=false`，`PlatformStatusVO.cronJobs` 注释为未落地。
- FastClaw baseline 将 `cron_jobs`、`agent_goals` 放在 P1。V13 先落 `cron_jobs` 与调度执行，`agent_goals` 只预留关联字段，不实现目标系统。
- 现有基础设施可复用：
  - `AgentRunCoordinator` 已支持会话 FIFO 与异步执行；
  - V9 身份与 owner 校验可直接用于 cron CRUD；
  - V11 channel runtime 已有 durable inbox/outbox 思路，但 V13 首版不把 cron 任务送入 Redis bus，避免扩大范围；
  - `TokenUsageHook` 已累加 run usage，定时运行天然入账。

## 3. 设计

### 3.1 M1：数据模型与 API 契约

新增 migration `V10__cron_jobs.sql`（实际版本号以 V12 已占用 migration 为准，落地时顺延）：

- `cron_jobs`
  - `id`：任务 ID；
  - `user_id`：任务属主，等于 agent owner；
  - `agent_id`：目标 agent；
  - `name`：显示名；
  - `type`：`cron` / `interval` / `once`；
  - `schedule`：原始表达式，按 type 解释；
  - `channel`、`chat_id`：触发上下文，默认 `web` + 自动生成的 scheduler session；
  - `message`：到点提交给 agent 的用户消息；
  - `enabled`：启停；
  - `status`：`IDLE` / `RUNNING` / `DISABLED` / `DEAD`；
  - `next_run_at`、`last_run_at`、`last_run_id`、`last_error`；
  - `claim_owner`、`claim_expires_at`：单机防重与后续多实例预留；
  - `created_at`、`updated_at`。

端点按前端契约实现：

- `GET /api/cron`：当前用户任务列表；super_admin 可看全量或先维持“自身 + 所有 agent 可读”视图，落地前按前端实际需要确定。
- `POST /api/cron`：创建任务，校验目标 agent 可写；返回任务对象。
- `PUT /api/cron/{id}`：更新 `enabled/name/schedule/message` 等可变字段；重算 `nextRun`。
- `DELETE /api/cron/{id}`：删除任务。
- `GET /api/agents/{id}/cron`：列出该 agent 的任务，owner 可读；公开 chatter 只读需求本期不开放。
- `PUT /api/agents/{id}/cron/{jobId}`：agent 内页启停。
- `DELETE /api/agents/{id}/cron/{jobId}`：agent 内页删除。

返回字段统一使用前端驼峰：`lastRun`、`nextRun`。错误口径复用现有 `ClientException`：越权 404，不合法表达式 400。

### 3.2 M2：Schedule 解析与持久化调度器

实现 `CronJobService` 与 `CronSchedulerWorker`：

- `type=once`：`schedule` 支持 ISO 时间或前端现有 exact time 字符串；执行成功后自动 `enabled=false`，不再重跑。
- `type=interval`：支持 `5m`、`1h`、`2d` 等紧凑格式；`next_run_at = now + interval`。
- `type=cron`：支持 5 段 cron（分钟级）；优先使用轻量库或 Spring 已有 cron 表达式能力，不手写复杂 cron parser。
- Scheduler 使用单线程 `ScheduledExecutorService` 每 10-30 秒扫描 due jobs：
  - 原子 claim `enabled=true AND next_run_at<=now AND (claim_expires_at IS NULL OR claim_expires_at<now)`；
  - claim 成功后提交 run；
  - 提交成功记录 `last_run_id/last_run_at`，并计算下一次 `next_run_at`；
  - 提交前失败进入短退避；连续失败超过阈值标记 `DEAD`；
  - 运行中的 agent turn 不由 cron worker 阻塞等待完整结束，避免调度线程被长任务占住。

重启恢复：

- 启动时清理过期 claim；
- 对 due jobs 重新扫描；
- 对 `RUNNING` 且 claim 过期的任务按“提交阶段失败”处理，不自动重跑已经成功创建 `agent_run` 的任务。

### 3.3 M3：Agent 自建任务工具

新增内置工具 `create_cron_job`，默认启用建议为 `false`，由 per-agent tool toggle 控制；如果 FastClaw 基线默认启用，落地前再按产品口径调整。

工具参数：

```json
{
  "name": "daily-report",
  "type": "cron|interval|once",
  "schedule": "*/5 * * * *|5m|2026-07-23T18:00:00+08:00",
  "message": "到点后发给 agent 的任务内容",
  "channel": "web",
  "chatId": "optional"
}
```

工具语义：

- 只能为当前 agent 创建任务；
- `user_id` 使用当前 run 的 owner；
- `channel/chatId` 默认沿用当前会话 scope，确保“聊天里创建的提醒”回到同一会话上下文；
- 创建成功返回任务 ID、下一次运行时间和可在 UI 管理的提示；
- 禁止创建空 message、过短 interval、过去时间 once；
- 工具结果必须足够短，避免污染上下文。

后续可补 `delete_cron_job`、`list_cron_jobs`，V13 先只交付 create，管理走 UI。

### 3.4 M4：执行上下文与会话策略

Cron 到点后不走 HTTP SSE，而是构造内部 `AgentRunCommand`：

- `userId`：任务属主；
- `agentId`：任务目标；
- `sessionId`：
  - agent 在聊天中创建且有 `chatId`：复用该 chat/session；
  - UI 创建但无 chatId：使用稳定 session `cron-{jobId}`；
- `channel`：默认 `web`，保留 IM channel 扩展字段；
- `message`：任务内容，建议加轻量系统标记或 metadata，前端历史可识别为 scheduled run。

结果交付：

- Web 端：结果写入 session history，用户打开对应会话可见；
- IM channel 主动推送：V13 不做。只保留 `channel/chatId` 字段，后续版本可接 channel outbox；
- Usage：沿用 agent run 的 token usage hook；
- Trace：`agent_runs` 与 `session_events` 可追溯 jobId（需要在 run metadata 或 message data 中记录）。

### 3.5 M5：状态、能力与管理体验

- `/api/status.capabilities.cron=true`；
- `/api/status.cronJobs` 返回当前可见任务数；
- `/cron` 页面空状态、创建、启停、删除应无 404；
- `/agents/{id}/scheduler` 列表能展示 agent 自建与 UI 创建的任务；
- agent 删除时级联删除或软禁用其 cron jobs，避免孤儿任务继续触发；
- user 停用时其 cron jobs 不执行，恢复后可继续。

## 4. 里程碑

- **M1（约 0.5 天）**：migration + record/repository + API CRUD + 前端契约联调，页面不再 404。
- **M2（约 1 天）**：schedule parser + nextRun 计算 + 单机 Scheduler Worker + 重启恢复 + claim 防重。
- **M3（约 0.5 天）**：`create_cron_job` 工具 + tool catalog/enablement + agent scheduler 页联调。
- **M4（约 0.5 天）**：内部提交 AgentRun + session/history/usage/trace 贯通。
- **M5（约 0.5 天）**：capability/status、删除/停用联动、错误口径、手工 smoke。

## 5. 发布门禁

- `JAVA_HOME='D:\software\Java\java17' ./mvnw verify` 全绿；
- CRUD 权限测试：用户 B 访问用户 A 的 cron job 一律 404；普通用户不能为他人 agent 创建任务；
- schedule 解析测试：`cron`、`interval`、`once` 正常与非法表达式覆盖；
- 执行测试：due job 只创建一次 run；`once` 成功后自动禁用；`interval/cron` 成功后推进 nextRun；
- 恢复测试：过期 claim 可被恢复；进程重启后 due job 不丢；
- UI smoke：`/cron` 创建任务，`/agents/{id}/scheduler` 展示并可启停/删除；
- Agent smoke：聊天中请求创建提醒，模型调用 `create_cron_job`，任务出现在 scheduler 页。

## 6. 明确不交付

- Redis/多 Pod cron 分布式调度（V13 保留 claim 字段，但默认单机 worker）；
- IM channel 主动推送定时结果；
- `agent_goals` 目标系统；
- timezone 偏好 UI 与复杂自然语言时间解析；
- `delete_cron_job` / `list_cron_jobs` 工具；
- 人工重放、失败任务后台管理、DLQ 页面；
- 秒级 cron 与高频任务，最小调度粒度建议 1 分钟。

## 7. 待审核问题

1. `create_cron_job` 默认是否启用？保守建议默认关闭，由用户在工具页启用。
2. UI 创建的任务是否应该复用一个固定 session `cron-{jobId}`，还是创建时强制选择某个现有 chat？
3. `type=exact` 前端值是否统一映射为后端 `once`？建议后端接受 `exact` 作为兼容别名，存储时归一为 `once`。
4. super_admin 的 `/api/cron` 是否查看全平台任务，还是只看自己的任务？建议全平台可见，普通用户仅自身。
