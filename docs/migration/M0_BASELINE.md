# M0 Migration Baseline

This document freezes the first migration inventory from the Go implementation at `D:\resources\code\fastclaw`. It is a tracking baseline, not a claim that the listed behavior is already implemented in Java.

## Source Scale

| Item | Baseline |
|---|---:|
| Go source files | 302 |
| Go test files | 79 |
| Registered HTTP routes | approximately 133 |
| Core data tables | 21 |
| Reused frontend source files | 123 |

Largest Go areas by approximate source lines:

| Package | Files | Lines |
|---|---:|---:|
| `internal/agent` | 85 | 19,840 |
| `internal/setup` | 28 | 10,709 |
| `internal/store` | 17 | 6,594 |
| `internal/channels` | 14 | 4,718 |
| `internal/sandbox` | 13 | 3,925 |
| `internal/gateway` | 11 | 3,862 |

## Data Tables

P0 tables:

- `users`
- `web_sessions`
- `apikeys`
- `apikey_agents`
- `agents`
- `sessions`
- `session_messages`
- `session_events`
- `agent_files`
- `configs`
- `token_usage_daily`
- `token_usage_log`
- `quotas`

P1 tables:

- `agent_knowledge_chunks`
- `cron_jobs`
- `agent_goals`

Compatibility-only in V1:

- `push_devices`
- `projects`
- `project_runtimes`
- `channel_leases`
- `channels`

## Endpoint Priorities

P0 endpoint groups:

- Health, readiness, and platform status
- Onboarding, login, logout, current user, and password change
- Agent CRUD, scoped config, system files, and regular files
- Provider CRUD, provider testing, and effective config
- Web chat stream, subscribe, steer, sessions, and history
- Skills, MCP tools, API keys, usage, and quotas
- OpenAI-compatible chat, agent listing, and upstream user provisioning

P1 endpoint groups:

- User administration
- Basic cron CRUD
- Knowledge file storage
- Public agents and skills
- Registration policy

Deferred route groups must remain stable and return a capability response or `FEATURE_NOT_AVAILABLE`; they must not disappear as 404s.

## Event Contract

The Java implementation must inventory and fixture these event families before chat migration is accepted:

- assistant text deltas
- reasoning deltas
- tool call and tool result events
- usage events
- completion and error events
- asynchronous subscription events
- OpenAI-compatible SSE chunks and `[DONE]`

## M0 Deliverables Status

| Deliverable | Status |
|---|---|
| Maven multi-module skeleton | Complete |
| Java 17 and Spring Boot 3.5.7 baseline | Complete |
| Reused FastClaw frontend | Complete |
| Health and status vertical slice | Complete |
| Capability contract | Complete |
| Module boundary test | Complete |
| SQLite/PostgreSQL test harness | Pending M1 |
| Executable Go/Java differential runner | Pending |
| Full route-level request/response fixture set | Pending |

