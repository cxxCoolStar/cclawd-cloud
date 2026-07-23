# OpenAgent Java V11：Agent Run 恢复与 Inbox Claim 续租优化

- 状态：技术债 / 待实施
- 记录日期：2026-07-23
- 关联方案：`OPENAGENT_JAVA_V11_PLAN.md`
- 范围：Channel Inbox、Agent Run、Worker Claim、Recovery Scheduler

## 1. 背景

V11 在 Worker 成功认领 Inbox 后立即 ACK Redis。此后任务可靠性由 PostgreSQL 中的
`PROCESSING`、`claimed_by`、`claim_expires_at` 和 `run_id` 承担。

当前实现能够恢复 Worker 宕机后长期停留在 `PROCESSING` 的记录，但恢复决策较粗：

```text
claim 过期且 run_id 为空    -> RETRY_WAIT
claim 过期且 run_id 不为空  -> INTERRUPTED
```

该策略避免对已经启动的 Agent 进行盲目重跑，但不能准确判断 Agent Run 的真实状态，
也没有覆盖长任务续租和旧 Worker 延迟回写等情况。

## 2. 当前实现

相关代码：

- `ChannelAgentWorker.CLAIM_TTL` 固定为 12 分钟。
- `ChannelAgentWorker` 启动 Agent 后调用 `attachRun()`，再次把
  `claim_expires_at` 设置为当前时间加 12 分钟。
- Agent 运行期间没有 Inbox Claim 心跳续租。
- `ChannelRecoveryScheduler` 每 2 秒扫描过期 Claim。
- `ChannelMessageRepository.recoverExpiredInbound()` 仅根据 `run_id` 是否为空决定
  `RETRY_WAIT` 或 `INTERRUPTED`。
- `ChannelMessageRepository.completeInbound()` 没有检查状态更新影响行数，旧 Worker
  在 Claim 已失效后仍可能继续创建 Outbox。

## 3. 已识别问题

### 3.1 固定 Claim TTL 可能误杀长任务

Agent 执行超过 12 分钟时，即使 Worker 仍然健康，Recovery 也可能将 Inbox 从
`PROCESSING` 改为 `INTERRUPTED`。

可能导致：

- 正在运行的任务被错误标记为中断；
- 后续消息提前成为 conversation 队首；
- 旧任务完成后继续回写结果，与新任务产生时序冲突；
- 用户看见任务失败，但稍后又收到旧任务回复。

### 3.2 `run_id IS NULL` 不能证明 Agent 从未启动

当前调用顺序存在窗口：

```text
startWithResult() 成功，Agent 已经启动
    -> Worker 尚未执行 attachRun()
    -> Worker 宕机
    -> Inbox.run_id 仍为空
    -> Recovery 将任务置为 RETRY_WAIT
    -> 同一用户任务可能再次启动
```

因此，`run_id` 为空只能表示“关联关系尚未成功持久化”，不能百分之百证明 Agent
没有执行或没有产生外部副作用。

### 3.3 仅凭 `run_id` 是否存在无法完成 Run 对账

`run_id` 不为空时，Run 可能处于以下任一状态：

- 已完成，但 Completion 尚未写入 Inbox/Outbox；
- 仍由健康执行器运行；
- 执行器已经宕机；
- 明确失败；
- 状态未知。

当前实现将这些情况统一标记为 `INTERRUPTED`，会丢失可以恢复的成功结果，也无法区分
真正失败和暂时失联。

### 3.4 旧 Worker 完成结果缺少 Fencing

Recovery 修改 Inbox 状态后，旧 Worker 的 Completion 回调仍可能到达。

当前 `completeInbound()` 虽然使用：

```sql
UPDATE ... WHERE id = ? AND status = 'PROCESSING'
```

但没有检查 UPDATE 的影响行数，随后仍会尝试插入 Outbox。这意味着失去 Claim
所有权的 Worker 可能提交过期结果。

### 3.5 Agent 和工具副作用不能靠任务重试自动消除

Agent Run 可能调用邮件、审批、订单、数据库写入等外部工具。即使 Inbox 和 Run
本身具备唯一约束，从头重跑仍可能重复外部副作用。

## 4. 目标方案

采用“持续续租 + Run 状态对账 + Fencing + 工具幂等”的恢复模型。

### 4.1 将 Run 预留和 Inbox 绑定前置

在真正调度 Agent 前，先创建或预留 durable Run，并在同一数据库事务内绑定 Inbox：

```text
事务：
1. 原子认领 Inbox
2. 创建状态为 RESERVED/QUEUED 的 Agent Run
3. 写入 Inbox.run_id
4. 提交

事务提交后：
5. 调度 Agent Run
```

如果现有模块边界无法在一个事务中完成，至少需要使用稳定的幂等键，例如：

```text
channel-inbox:{inbox_id}
```

`AgentRunCoordinator` 重复收到同一幂等键时，必须返回同一个 Run，而不是创建新 Run。

### 4.2 Agent 运行期间续租

Worker 或 Run Executor 在任务处于活动状态时定期续租：

```text
claim TTL：可配置，建议 2～5 分钟
renew interval：TTL 的 1/3 左右
```

续租必须是带所有权条件的原子更新：

```sql
UPDATE channel_message_inbox
   SET claim_expires_at = :newExpiry,
       updated_at = :now
 WHERE id = :inboxId
   AND status = 'PROCESSING'
   AND claimed_by = :workerId
   AND claim_version = :claimVersion;
```

更新零行表示 Worker 已失去所有权，必须停止提交 Completion。

### 4.3 引入 Claim Version/Fencing Token

每次重新认领时递增 `claim_version`。启动 Run、续租和完成回写都携带该版本：

```text
inbox_id + claimed_by + claim_version
```

只有仍持有当前版本的 Worker 才能：

- 续租；
- 标记完成或失败；
- 创建 Outbox；
- 推进 conversation 的下一条消息。

`completeInbound()` 必须先确认条件 UPDATE 影响一行，再创建 Outbox；两步放在同一事务。
更新零行时返回 stale-completion 结果，不允许继续写 Outbox。

### 4.4 Recovery 根据 Run 状态对账

Recovery 发现过期 Inbox Claim 后，不直接根据 `run_id` 是否为空决定状态，而是查询
durable Agent Run：

| Inbox/Run 状态 | Recovery 动作 |
|---|---|
| 未绑定 Run，且幂等键查不到 Run | 进入 `RETRY_WAIT` |
| 未绑定 Run，但幂等键能找到 Run | 补写 `run_id`，继续对账 |
| Run 为 `QUEUED`，尚未执行 | 重新调度同一个 Run |
| Run 为 `RUNNING`，且执行器健康 | 不接管，等待续租或短暂延长观察期 |
| Run 为 `RUNNING`，执行器失联 | 尝试恢复同一个 Run；不能恢复则 `INTERRUPTED` |
| Run 已 `COMPLETED` 且结果已持久化 | 原子完成 Inbox 并补建 Outbox |
| Run 已明确失败 | 将 Inbox 标记为对应失败终态 |
| Run 状态不确定 | 标记 `INTERRUPTED`，记录原因并等待人工重放 |

对账操作本身必须幂等，多实例 Recovery 同时执行时只能有一个实例成功改变状态。

### 4.5 工具调用幂等

每次有副作用的工具调用使用稳定幂等键：

```text
run_id + tool_call_id
```

工具执行结果先持久化，再返回给 Agent。重复调用同一幂等键时返回第一次结果，不再次
执行外部操作。对于不支持幂等键的第三方接口，需要：

- 调用前后记录审计状态；
- 支持按业务唯一键查询执行结果；
- 无法确认结果时进入人工处理，而不是自动重试。

## 5. 推荐实施顺序

### P0：修复过期 Completion 回写

1. `completeInbound()` 检查条件 UPDATE 的影响行数。
2. 只有更新成功时才允许插入 Outbox。
3. Completion 校验 `run_id` 与当前 Inbox 绑定一致。
4. 增加“Recovery 已中断后旧 Worker 返回”的并发测试。

### P1：Claim 心跳续租和配置化

1. 将 Claim TTL、续租间隔移到配置项。
2. Agent Run 活动期间定时续租。
3. Run 结束或 Worker 关闭时停止续租任务。
4. 增加续租失败、Worker 暂停和长任务超过原 12 分钟的测试。

### P2：Run 幂等创建与状态对账

1. 使用 Inbox ID 作为 Run 创建幂等键。
2. 消除 `startWithResult()` 与 `attachRun()` 之间的重复启动窗口。
3. Recovery 根据 Run 状态执行对账矩阵。
4. 已完成 Run 可以补建 Outbox，而不重新执行 Agent。

### P3：Fencing 和工具副作用幂等

1. 增加 `claim_version` 或等价 fencing token。
2. 所有续租、完成和失败回写校验 fencing token。
3. 为有副作用的工具调用增加 `run_id + tool_call_id` 幂等机制。
4. 为无法自动确认的外部操作提供人工重放和审计入口。

## 6. 可观测性

至少增加以下指标：

- `channel_inbox_claim_renew_total{result}`
- `channel_inbox_claim_expired_total{run_state}`
- `channel_run_reconcile_total{action,result}`
- `channel_stale_completion_total`
- `channel_run_start_deduplicated_total`
- `tool_call_idempotency_hit_total`
- Agent Run 执行时长 P50/P95/P99
- `PROCESSING` 年龄和超过 Claim TTL 的任务数量

告警建议：

- 续租失败持续增长；
- `stale_completion` 非零；
- `PROCESSING` 任务年龄超过预期最大执行时间；
- `INTERRUPTED` 比例突增；
- 同一 Inbox 出现多个 Run。

## 7. 验收场景

必须通过 PostgreSQL + Redis 多实例集成测试：

1. Worker 在认领后、创建 Run 前宕机，任务只创建一个 Run。
2. Worker 在 Run 创建后、绑定 Inbox 前宕机，Recovery 能找到同一个 Run。
3. Agent 执行超过 12 分钟时，续租阻止误恢复。
4. Worker 停止续租后，Recovery 在 TTL 内接管。
5. Recovery 接管后旧 Worker 返回结果，旧结果不能创建 Outbox。
6. Run 已完成但 Completion 未执行时，Recovery 补建唯一 Outbox。
7. 两个 Recovery 实例同时对账同一任务，只产生一次状态迁移。
8. 同一工具调用重放时，外部副作用只发生一次。
9. Run 状态无法确认时进入 `INTERRUPTED`，不进行危险的自动重跑。

## 8. 暂不处理

本优化不改变 V11 的以下设计：

- PostgreSQL 继续作为 Inbox、Run 和 Outbox 的事实来源；
- Redis Stream 继续只承担通知和分发；
- conversation FIFO 继续由数据库原子 Claim 保证；
- Outbox 发送失败不重新执行 Agent；
- 第三方渠道不支持幂等发送时，仍无法承诺严格 exactly-once。
