# OpenAgent Java V12: Channel Observability Console

## Scope

V12 adds a platform-admin, read-only operations console for the distributed
Channel runtime introduced in V11. The normal message path remains:

```text
Channel Gateway -> PostgreSQL Inbox -> Redis Inbound Stream
  -> Agent Worker -> PostgreSQL Outbox -> Redis Outbound Stream
  -> Channel Egress
```

The console reads PostgreSQL state and runtime lease information. A console
failure must not block or mutate the message path.

## M1: Read-only Observability

Status: implemented.

### API contract

All new endpoints require `super_admin`, return `Result<T>`, and use
`Results.success(...)`.

```text
GET /api/channels/accounts
GET /api/channels/summary
GET /api/channels/messages
GET /api/channels/messages/{messageId}
GET /api/channels/failures
GET /api/channels/runtime
```

Message lists accept:

```text
channelType, accountId, status, keyword, page, pageSize
```

`pageSize` is constrained to `1..100`. The top-level console is a global
platform view; account ownership is returned for diagnostics, while access is
guarded by the service-level `super_admin` check.

### Code structure

```text
bootstrap.channel.controller
bootstrap.channel.controller.request
bootstrap.channel.controller.vo
bootstrap.channel.service
bootstrap.channel.service.impl
bootstrap.channel.dao.entity
bootstrap.channel.dao.mapper

frontend/src/app/channels/page.tsx
frontend/src/features/channels/components
frontend/src/features/channels/hooks
frontend/src/features/channels/services
```

New persistence queries use MyBatis-Plus `DO + Mapper`. Controllers never call
the mapper directly. No new `JdbcTemplate Repository` was introduced.

### Data sources

- Accounts: `channel_bindings`
- Inbox state: `channel_message_inbox`
- Sender identity: `channel_conversations`
- Agent run correlation: `channel_message_inbox.run_id`
- Outbound state: `channel_message_outbox`
- Shared runtime heartbeat: `ChannelRuntimeRegistry`
- Lease presence: `ChannelLeaseService.isActive`

### Cluster runtime status

The console never treats the API Pod's local `ChannelRuntimeManager` state as
cluster state. An ingress owner writes a Redis Hash at
`{keyPrefix}:runtime:{bindingId}` after lease acquisition and every successful
lease renewal. The Hash contains:

```text
ownerId, adapterStatus, heartbeatAt, lastMessageAt, lastError
```

The heartbeat TTL is twice the lease TTL. Message timestamp updates and cleanup
use owner-checked Lua, so a stale Gateway cannot overwrite or delete the
replacement owner's runtime state. Heartbeat failures are best effort and never
stop polling or message persistence. The local bus uses the same contract via
`LocalChannelRuntimeRegistry`.

Account status is resolved from enabled state, lease presence, and heartbeat:

```text
disabled                         -> DISABLED
enabled, no lease                -> UNAVAILABLE
lease, no heartbeat              -> DEGRADED
heartbeat adapter=connecting     -> STARTING
heartbeat adapter=connected      -> HEALTHY
heartbeat adapter=degraded       -> DEGRADED
heartbeat adapter=expired/error  -> ERROR
```

No new database migration is required for M1.

## M2: Failure Recovery

M2 must not expose retry until all of these are implemented:

1. A delivery-attempt audit table using MyBatis-Plus.
2. A distributed retry lock scoped by message ID and stage.
3. State validation that rejects already delivered messages.
4. Maximum retry count and cooldown enforcement.
5. Operator ID, reason, previous state, and result auditing.
6. Concurrent retry and duplicate-delivery integration tests.

## Verification

- Java compilation runs in the local JDK 17 Docker image.
- `ChannelObservationEndpointsTest` verifies SQLite compatibility, the
  `Result<T>` contract, and the cluster-status response fields.
- `ChannelRuntimeRegistryTest` verifies local heartbeat retention and status
  resolution.
- `RedisChannelRuntimeRegistryTest` verifies that a stale owner cannot delete
  or update a replacement owner's heartbeat.
- The frontend passes ESLint and the Next.js production build.
- An API-only V12 instance can connect to the existing PostgreSQL and Redis
  without starting ingress, worker, or egress roles.
