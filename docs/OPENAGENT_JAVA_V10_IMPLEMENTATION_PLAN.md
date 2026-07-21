# OpenAgent Java V10 Implementation Plan

## Status

- Version: V10 implementation baseline
- Date: 2026-07-21
- Scope: WeChat iLink text channel and per-chatter conversation and memory isolation
- Precondition: V9 multi-user ownership checks remain authoritative

## Decisions

1. A platform owner and a WeChat chatter are different identities. `ownerUserId` remains the identity used for authorization and agent configuration. A `ChannelContext` carries external channel identity and is never substituted for `ownerUserId`.
2. Web sessions retain their existing behavior. Channel conversations map external identities to an internal session ID instead of changing web-facing session semantics.
3. A channel conversation is unique by `(binding_id, chat_id)`. A binding uniquely identifies `(owner_user_id, channel, account_id, agent_id)`.
4. Per-chatter memory uses a generated internal conversation scope ID. Raw external IDs must not be used as filesystem path components.
5. Inbound delivery is at-least-once. A persistent idempotency record keyed by `(binding_id, external_message_id)` guarantees one Agent run per accepted inbound message.
6. The first release supports text direct messages only. It does not support group routing, media, multi-instance channel leasing, or a cross-process event bus.

## Phase 0: Contract And Spike

Goal: remove iLink protocol uncertainty before application integration.

Tasks:

- Define request and response DTOs from verified iLink examples.
- Confirm authentication refresh, polling cursor, acknowledgement behavior, send-message endpoint, rate limits, and error codes.
- Define a fake iLink HTTP server for deterministic tests.
- Read the frontend channel API contract and either implement it or record every intentional contract change before backend work starts.

Exit criteria:

- Captured protocol fixtures cover a normal poll, empty poll, duplicate message, expired credential, retryable error, and send response.
- A local fake server can drive adapter tests without real credentials.

## Phase 1: Conversation Scope And Persistence

Goal: establish correct isolation before accepting external messages.

Tasks:

- Add a channel-context value object carrying `channel`, `accountId`, `chatId`, `chatterId`, and a generated conversation scope ID.
- Add an internal coordinator entry point that takes `ownerUserId` explicitly. Existing HTTP entry points continue to use `RequestContext` only at their boundary.
- Add `channel_bindings`, `channel_conversations`, and `channel_inbound_messages` migrations and repositories.
- Resolve a binding, atomically deduplicate an inbound message, then create or reuse its internal session.
- Ensure the FIFO key remains based on the resolved internal session ID.
- Add safe migration defaults and indexes for SQLite and PostgreSQL.

Exit criteria:

- Two accounts with the same external chat ID obtain different internal sessions.
- Two chatters under one binding do not share an internal session.
- Replaying an inbound message ID does not create another run.
- Existing Web session and run tests pass unchanged.

## Phase 2: Scoped Memory

Goal: prevent context and memory leakage between external conversations.

Tasks:

- Propagate the optional channel context through conversation construction, memory injection, auto-persist, and memory search.
- Use the generated scope ID for channel memory storage; retain current agent-level Web paths.
- Keep workspace paths session-scoped and do not derive them from external identifiers.
- Reject malformed or oversized external identifiers at the adapter boundary.

Exit criteria:

- Chatter A cannot retrieve Chatter B memory through prompt injection, memory search, or direct service calls.
- Auto-persist writes to the same scope that memory injection and search read.
- Web memory behavior is unchanged.

## Phase 3: Channel Runtime And WeChat Adapter

Goal: run a single-account WeChat long-poll lifecycle safely in one process.

Tasks:

- Define `ImChannelAdapter` with lifecycle, status, inbound callback, and account-aware send operations.
- Implement the WeChat adapter with one managed polling worker per enabled binding or account.
- Use bounded exponential backoff with jitter for retryable failures and expose terminal authentication/configuration failures as degraded status.
- Persist cursor state only after accepted messages have been durably deduplicated and submitted.
- Implement account-aware outbound text sending and capture the provider response ID.

Exit criteria:

- Adapter parser, cursor, retry, stop, and send tests run against the fake server.
- A duplicate delivery creates one run even across retry paths.
- Adapter shutdown stops new polls and waits for the active request within a bounded timeout.

## Phase 4: End-To-End Turn Bridge

Goal: deliver one inbound message to one Agent run and one final outbound reply.

Tasks:

- Resolve binding and conversation, submit through the explicit-owner coordinator path, and preserve per-session FIFO ordering.
- Attach a run-specific event sink or completion result collector. Do not subscribe broadly to replayable chat events for outbound replies.
- Aggregate the final text response and send it once after terminal completion.
- Retry only outbound send failures. Never rerun an Agent turn as a consequence of outbound retry.
- Add inbound/outbound audit events with channel, binding, external message ID, run ID, and safe error details.

Exit criteria:

- Mock end-to-end test covers inbound text through a final outbound text reply.
- Queued turns reply in their original order for a single conversation.
- A transient outbound failure retries send without duplicate model or tool execution.

## Phase 5: Management API, Frontend, And Release

Goal: make the channel operable without exposing credentials.

Tasks:

- Implement owner-authorized binding CRUD and adapter connection-status endpoints.
- Store credentials using existing configuration security conventions and return masked values only.
- Wire `PlatformCapabilities.channels` to the delivered API behavior.
- Complete the existing frontend channel pages against the verified contract.
- Update operational configuration, single-instance limitation, smoke-test instructions, and recovery guidance.

Exit criteria:

- Owner authorization rejects access to another owner's bindings and status.
- Frontend can create, update, inspect, and disable a binding.
- Real-device smoke test verifies two WeChat users do not share history or memory.

## Required Tests

- Migration and repository tests for uniqueness, ownership, and idempotency.
- Coordinator tests proving external turns do not require `RequestContext`.
- Memory isolation tests across accounts, chats, and chatters.
- Adapter tests for cursor handling, duplicate input, reconnect backoff, and send failures.
- End-to-end fake-iLink integration tests.
- Full `mvnw verify`, frontend build, and manual real-device smoke test.

## Delivery Order

1. Phase 0 contract fixtures.
2. Phase 1 persistence and explicit coordinator path.
3. Phase 2 scoped memory.
4. Phase 3 adapter runtime.
5. Phase 4 turn bridge.
6. Phase 5 management surface and production validation.

Estimated engineering effort is five to seven working days, excluding external iLink access delays and real-device credential provisioning.
