# FastClaw Redis 多副本实现 PR 评估

- 评估日期：2026-07-22
- 参考版本：`fastclaw` `dev` 分支，Redis coordination 引入提交 `267509f`
- 评估范围：`internal/bus`、`internal/rediscoord`、`internal/gateway`、`internal/channels`、`internal/taskqueue`

## 1. 结论

FastClaw 已具备 Redis Stream 入站/出站总线和 Redis channel lease，但当前实现更接近“跨副本分摊消息”的第一版，尚未形成端到端可靠投递。

适合直接提交的小型 PR 有四类：

1. Redis bus 与 lease 行为测试。
2. Redis `XADD` 失败后的重试和背压。
3. Pending 消息回收、毒消息隔离和死信。
4. Redis 启用时的跨副本 IM 入站去重。

ACK 生命周期、跨 Pod 会话 FIFO、持久化 outbound outbox、Gateway/Worker 角色拆分会改变跨模块契约，应先提交 Issue/RFC，再按阶段实现。

## 2. 已确认的风险

### 2.1 发布失败会直接丢消息

`publishInbound` 和 `publishOutbound` 从本地 Go channel 取出消息后调用 `XADD`。调用失败时只记录日志，消息不会重新入队，也没有持久化来源可供恢复。

影响：Redis 短暂超时或故障切换即可造成入站请求或 Agent 回复永久丢失。

### 2.2 Redis ACK 早于业务完成

消费者把消息写入本地 buffered channel 后立即执行 `XACK`：

- inbound 尚未完成 owner/binding 解析、去重、taskqueue 提交或 Agent 执行；
- outbound 尚未查找 adapter，更没有完成第三方 API 发送。

影响：Redis 已认为消息完成，但进程在本地处理前崩溃时无法恢复。

### 2.3 Pending 消息不会被其他副本接管

消费者只使用 `XREADGROUP` 的 `>` 读取新消息，没有 `XPENDING`、`XAUTOCLAIM` 或 `XCLAIM`。消费者崩溃前留下的 pending 消息不会被新的 consumer 主动回收。

反序列化失败的 payload 也不会 ACK 或进入死信，会长期滞留 PEL。

### 2.4 去重只在单进程有效

Gateway 的 dedup 使用进程内 `sync.Map` 和 60 秒 TTL。两个副本在 lease 短暂重叠、旧 poll 恢复或第三方平台重投时，仍可能分别执行一次 Agent。

DM 已有稳定 `(channel, accountID, messageID)`，适合使用 Redis `SET NX PX`。群聊当前采用文本哈希启发式，存在误伤用户连续发送相同文本的可能，不应与 DM 修复混在同一个 PR。

### 2.5 同一会话不能保证跨 Pod 串行

Redis consumer group 可以把同一 chat 的相邻消息交给不同 Pod。FastClaw 的 `taskqueue.Queue` 只在本进程按 `channel:accountID:chatID` 串行，因此两个 Pod 仍可能并发执行同一会话。

### 2.6 出站失败不可持久恢复

`channels.Manager` 在 `SendMessage` 失败后只记录日志。此时 Redis 消息已经 ACK，Agent 不会重跑，但回复也不会再次投递。

### 2.7 固定 Stream 裁剪可能破坏 backlog

`XADD MAXLEN ~ 10000` 是固定值。长时间积压时 Redis 可以裁掉仍在 PEL 中的底层 entry，只留下无法恢复 payload 的 pending ID。

## 3. 推荐 PR 拆分

### PR 1：Redis bus 与 lease 集成测试

建议标题：`test(redis): cover stream bus and channel lease`

范围：

- consumer group 在两个 consumer 间只分发一次；
- inbound/outbound 序列化往返；
- `web`、`api` 保持 local-only；
- lease acquire、renew、非 owner release、TTL takeover；
- Redis 关闭时现有内存 bus 行为不变。

该 PR 不改变生产行为，最容易 review，也为后续修复建立保护网。

### PR 2：Redis 发布重试与背压

建议标题：`fix(bus): retry Redis stream publication instead of dropping`

范围：

- `XADD` 使用带 jitter 的有界指数退避；
- 重试期间保留消息并对生产者形成背压；
- context 取消时停止重试；
- 暴露连续失败和最后成功时间日志/指标；
- 测试 Redis 短暂不可用后恢复，消息最终只写入一次。

不要在失败后回退为本地消费，否则多副本下会绕开共享调度语义。

### PR 3：Pending recovery 与 DLQ

建议标题：`fix(bus): reclaim stale Redis stream deliveries`

范围：

- 启动和周期任务执行 `XAUTOCLAIM`；
- 记录 delivery count；
- 无 payload、类型错误、JSON 错误进入 `<stream>:dead` 后 ACK；
- 超过最大次数进入 DLQ；
- pending idle time、扫描批次和最大次数可配置。

该 PR 修复 Redis 层 pending，但不能宣称已经解决业务处理阶段的崩溃丢失，因为当前 ACK 仍然过早。

### PR 4：跨副本 DM 去重

建议标题：`fix(gateway): deduplicate IM messages across Redis replicas`

范围：

- 抽象 `Deduper`，保留内存默认实现；
- Redis 模式用 `SET key value NX PX ttl`；
- key 为 `(channel, accountID, messageID)` 的哈希，避免原始用户标识泄露；
- Redis 错误采用明确的 fail-open 或 fail-closed 策略，并记录指标；
- 只覆盖具有稳定 message ID 的 DM。

## 4. 应先讨论的架构改造

以下内容不建议作为“顺手修复”直接提交：

1. 显式 `Delivery<T>`/`AckHandle` 消费接口，把 ACK 延迟到业务持久化之后。
2. 持久化 inbox/outbox，保证进程崩溃后仍能继续投递。
3. 跨 Pod per-chat FIFO 或 conversation head-of-line claim。
4. Gateway、Agent Worker、Channel Egress 独立运行角色。
5. Provider 不支持幂等键时的重复发送策略。

这些变更需要先约定可靠性目标：at-most-once、at-least-once，还是“运行最多一次、发送至少一次”。

## 5. 不应过度承诺的语义

- Redis Stream consumer group 本身不等于端到端可靠投递。
- Channel lease 只能减少重复 poll，不能替代持久化去重。
- 第三方发送成功、进程在记录成功前崩溃时，如果平台不支持 idempotency key，重复发送无法完全避免。
- Agent 工具可能产生外部副作用，运行中断后不应默认自动重跑整个 turn。

## 6. 上游验收建议

- Redis 在 `XADD` 前后分别断开，消息均有明确结果。
- consumer 在 read、local enqueue、业务处理三个时间点分别崩溃。
- 两个 Gateway 同时收到同一个 DM，只执行一次 Agent。
- lease holder 停止后，另一个副本在 TTL 内接管。
- 毒消息不会无限占用 PEL。
- backlog 超过裁剪阈值时，未完成消息不会静默消失。

