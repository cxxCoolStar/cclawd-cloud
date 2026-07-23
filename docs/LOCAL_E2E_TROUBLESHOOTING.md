# OpenAgent V11 本地端到端运行与排障

本文用于本地验证以下完整链路：

```text
微信接收 -> PostgreSQL Inbox -> Redis inbound -> Agent Worker
-> 模型/工具调用 -> PostgreSQL Outbox -> Redis outbound -> 微信发送
```

## 1. 启动与关闭

前置条件：

- Docker Desktop 已启动，`docker compose version` 可执行。
- 仓库根目录存在 `.env`，至少配置非空的 `OPENAGENT_MODEL_API_KEY`。
- 不要提交 `.env`、`cookies.txt`、微信 token 或任何 API Key。

启动全部环境：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\start.ps1
```

默认启动会优先使用本机 JDK 17 和 Maven Wrapper 生成带 `exec` classifier 的可执行 JAR；
找不到有效 JDK 时，回退到使用 `OPENAGENT_LOCAL_DNS` 的临时 JDK 容器。

跳过镜像构建、直接启动已有镜像：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\start.ps1 -SkipBuild
```

`-SkipBuild` 要求 `openagent:v11-local` 镜像已经由至少一次成功的默认启动构建。

正常关闭并保留 PostgreSQL/Redis 数据：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\shutdown.ps1
```

旧的手工 V11 容器占用 `18956/15432/16379` 时：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\shutdown.ps1 -IncludeLegacy
```

旧手工容器与 `openagent-local` 使用不同的数据卷。首次切换后需要重新完成微信登录，
或在人工审核后使用 `pg_dump/pg_restore` 迁移；脚本不会自动复制包含微信凭据的数据。

永久删除本地 PostgreSQL 与 Redis 数据需要双重显式确认：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\deploy\local\shutdown.ps1 -PurgeData -Force
```

默认地址：

| 服务 | 地址 |
|---|---|
| OpenAgent | `http://127.0.0.1:18956` |
| Readiness | `http://127.0.0.1:18956/actuator/health/readiness` |
| PostgreSQL | `127.0.0.1:15432` |
| Redis | `127.0.0.1:16379` |

可在 `.env` 中使用 `OPENAGENT_LOCAL_APP_PORT`、`OPENAGENT_LOCAL_POSTGRES_PORT`、
`OPENAGENT_LOCAL_REDIS_PORT`、`OPENAGENT_LOCAL_DATABASE_PASSWORD` 和
`OPENAGENT_LOCAL_DNS` 覆盖本地默认值。

## 2. 五分钟分层定位

以下命令均在仓库根目录执行。先定义 Compose 参数，避免遗漏项目名：

```powershell
$dc = @(
  "compose", "--project-name", "openagent-local",
  "--env-file", ".env", "--file", "deploy/local/compose.yaml"
)
```

### 2.1 容器和依赖是否健康

```powershell
docker @dc ps
Invoke-RestMethod http://127.0.0.1:18956/actuator/health/readiness
docker @dc logs --tail 120 postgres redis app
```

预期结果：三个服务为 `running/healthy`，readiness 为 `UP`。

### 2.2 微信消息是否进入 Gateway

```powershell
docker @dc logs --since 10m app 2>&1 |
  Select-String -Pattern "WeChat poll received|WeChat inbound received|Inbound persisted"
```

关键字段：

- `externalMessageId`：微信消息去重 ID。
- `inboxId`：后续数据库、Redis 和 Worker 排查的主线 ID。
- `conversationId`、`sequenceNo`：同一会话 FIFO 排序依据。

没有日志时依次检查：

1. 是否存在 `WeChat polling started`。
2. 是否存在 `Binding lease acquired`。
3. 是否持续出现 `WeChat poll failed`。
4. Redis 中是否存在 `openagent:channel:lease:<bindingId>`。

### 2.3 Inbox、Redis 和 Worker 是否接力

```powershell
docker @dc logs --since 10m app 2>&1 |
  Select-String -Pattern "Inbound persisted|Inbound published|Inbound notification received|Inbound claimed|Agent run attached"
```

Redis Stream 状态：

```powershell
docker @dc exec -T redis redis-cli XLEN openagent:channel:inbound
docker @dc exec -T redis redis-cli XPENDING openagent:channel:inbound openagent:channel:inbound-workers
docker @dc exec -T redis redis-cli XLEN openagent:channel:inbound:dlq
```

解释：

- Stream 有记录而 `pending=0` 通常表示 Worker 已 ACK；业务是否完成仍以 PostgreSQL 为准。
- pending 长时间不降时检查 Worker、数据库连接和 `XAUTOCLAIM`/claim 日志。
- DLQ 非零时检查通知是否缺少 `recordId`。

### 2.4 Agent Run 是否成功

```powershell
docker @dc logs --since 10m app 2>&1 |
  Select-String -Pattern "\[agentrun\]|\[kernel\]|\[llm\]|Agent run not deliverable|运行失败"
```

正常过程至少包含：

```text
运行开始 -> 调用模型 -> 模型调用完成 -> 运行结束 status=COMPLETED
```

终态规则：

- `COMPLETED` 或 `LIMIT_REACHED` 且最终内容非空：允许创建 Outbox。
- `FAILED`、`TIMED_OUT`、`INTERRUPTED`：Inbox 必须进入 `INTERRUPTED`，不能误标 `COMPLETED`。
- `COMPLETED` 但最终内容为空：同样视为不可投递。

### 2.5 Outbox 是否发送到微信

```powershell
docker @dc logs --since 10m app 2>&1 |
  Select-String -Pattern "Outbound persisted|Outbound published|Outbound claimed|Reply delivery|WeChat send"
```

成功终点：

```text
WeChat send completed
Reply delivery completed
```

Redis outbound 状态：

```powershell
docker @dc exec -T redis redis-cli XLEN openagent:channel:outbound
docker @dc exec -T redis redis-cli XPENDING openagent:channel:outbound openagent:channel:outbound-workers
docker @dc exec -T redis redis-cli XLEN openagent:channel:outbound:dlq
```

## 3. PostgreSQL 对账

先查看最近 Inbox，不查询消息正文：

```powershell
docker @dc exec -T postgres psql -U openagent -d openagent -P pager=off -c @"
SELECT id, external_message_id, conversation_id, sequence_no, status,
       attempts, run_id, last_error, created_at
  FROM channel_message_inbox
 ORDER BY created_at DESC
 LIMIT 10;
"@
```

按 `inboxId` 对账 Inbox、Run 和 Outbox：

```powershell
$inboxId = "替换为日志中的 inboxId"
docker @dc exec -T postgres psql -U openagent -d openagent -P pager=off -c @"
SELECT i.id AS inbox_id, i.status AS inbox_status, i.attempts AS inbox_attempts,
       i.run_id, r.status AS run_status, r.error_code, r.error_message,
       o.id AS outbox_id, o.status AS outbox_status, o.attempts AS send_attempts,
       o.last_error AS send_error, length(o.text) AS reply_length
  FROM channel_message_inbox i
  LEFT JOIN agent_runs r ON r.id = i.run_id
  LEFT JOIN channel_message_outbox o ON o.inbox_id = i.id
 WHERE i.id = '$inboxId';
"@
```

典型判断：

| Inbox | Run | Outbox | 含义 |
|---|---|---|---|
| `PROCESSING` | `RUNNING` | 无 | Agent 尚在执行或进程中断 |
| `INTERRUPTED` | `FAILED/TIMED_OUT/INTERRUPTED` | 无 | Agent 失败，检查模型或工具错误 |
| `COMPLETED` | `COMPLETED` | `READY/PUBLISHED/DELIVERING` | 回复待发送 |
| `COMPLETED` | `COMPLETED` | `SENT` | 完整链路成功 |
| `COMPLETED` | `COMPLETED` | `RETRY_WAIT/DEAD` | 微信发送失败，不应重新执行 Agent |

## 4. DNS 与网络故障

本地曾出现 Docker Desktop 内置 DNS `192.168.65.7` 首次查询超时：IPv4 约 5 秒，
双栈 Java 请求约 10 秒后抛出 `UnresolvedAddressException`。应用运行阶段通过 `OPENAGENT_LOCAL_DNS` 显式配置 DNS，默认值为 `114.114.114.114`；未安装本机 JDK 时，临时 Maven 构建容器也使用该 DNS。

检查 resolver 和模型域名：

```powershell
docker @dc exec -T app cat /etc/resolv.conf
docker @dc exec -T app getent hosts api.lkeap.cloud.tencent.com
docker @dc exec -T app curl -sS -o /dev/null `
  -w "http=%{http_code} dns=%{time_namelookup}s total=%{time_total}s`n" `
  --connect-timeout 10 https://api.lkeap.cloud.tencent.com/coding/v3/models
```

不带 API Key 探测时 `401` 是正常结果，表示 DNS、TCP 和 TLS 均已连通。不要在命令行加入真实 Key。

判断方式：

- `dns` 小于 1 秒且返回 `401`：网络正常。
- `dns` 接近 5/10 秒：DNS 上游失效或 Docker Desktop 保留了陈旧网卡 DNS。
- `Could not resolve host` / `UnresolvedAddressException`：先修 DNS，不要修改模型代码。
- DNS 正常但连接超时：检查 VPN、防火墙和 443 端口。
- 返回 `401/403` 且应用实际调用也失败：检查 API Key 权限，但不得输出 Key。

宿主机对照：

```powershell
Resolve-DnsName api.lkeap.cloud.tencent.com -Type A
Test-NetConnection api.lkeap.cloud.tencent.com -Port 443
Get-DnsClientServerAddress -AddressFamily IPv4
```

## 5. 指标与长期观察

```powershell
Invoke-RestMethod http://127.0.0.1:18956/actuator/metrics/openagent.channel.inbox.backlog
Invoke-RestMethod http://127.0.0.1:18956/actuator/metrics/openagent.channel.outbox.backlog
Invoke-RestMethod http://127.0.0.1:18956/actuator/metrics/openagent.channel.inbox.interrupted
Invoke-RestMethod http://127.0.0.1:18956/actuator/metrics/openagent.channel.outbox.dead
Invoke-RestMethod http://127.0.0.1:18956/actuator/metrics/openagent.channel.leases.held
```

重点关注 backlog 持续增长、`interrupted/dead` 增量和租约频繁丢失。

## 6. 安全与操作边界

- 日志只记录 ID、状态、长度、耗时和尝试次数，不记录消息正文、API Key、微信 token 或完整凭据。
- 不使用 `docker inspect` 直接输出整个 Env；只筛选非敏感变量或只输出变量名。
- 不查询 `channel_bindings.credentials_json`，不打印 `.env` 内容。
- 不修改或提交 `cookies.txt`。
- Redis 只承担通知和分发，不能用 Stream 长度替代 PostgreSQL 业务状态。
- 不要直接把失败 Inbox 改回待处理；Agent 工具可能已经产生外部副作用。先确认 Run 和工具执行记录。
- 默认 shutdown 保留数据；只有明确不再需要本地微信绑定和消息记录时才使用 `-PurgeData -Force`。

## 7. 一次成功验证的标准

同时满足以下条件才算端到端成功：

1. 日志出现 `WeChat inbound received` 和唯一 `inboxId`。
2. Inbox 被 Worker claim，并绑定唯一 `runId`。
3. Run 终态为 `COMPLETED` 或带非空总结的 `LIMIT_REACHED`。
4. 创建唯一 Outbox，最终状态为 `SENT`。
5. 日志出现 `Reply delivery completed`。
6. Redis inbound/outbound consumer group 没有长期 pending。
7. 用户在微信端实际收到回复。
