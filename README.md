# OpenAgent

OpenAgent is an open-source, self-hosted AI Agent platform. The current **V0.6** milestone adds MCP client support on top of V0.5 skills: external MCP servers contribute tools to the agent. All while reusing the existing Next.js frontend.

## V0.6 Features

- **MCP client**: official MCP Java SDK; stdio (subprocess) and Streamable HTTP transports
- **Per-agent MCP servers**: `GET /api/agents/{id}/config` + `PUT /api/agents/{id}` (`mcpServers` whole-map replace), backed by the `agent_mcp_servers` table
- **Dynamic tools**: discovered MCP tools appear as `mcp_<server>_<tool>` alongside builtin tools; unreachable servers are skipped without breaking builtin tools
- **UTF-8 stdio transport**: custom transport fixes the SDK's platform-charset decoding (Chinese tool results were garbled on Windows)

## V0.5 Features

- **Skills**: a directory with a `SKILL.md` (YAML frontmatter + Markdown instructions) is a skill; global dir `./skills` plus per-agent `{workspaceRoot}/{agentId}/skills` (same-name shadowing)
- **Progressive disclosure**: the system prompt carries only a one-line catalog; the model loads full instructions via the built-in `load_skill` tool
- **Skill APIs**: `GET /api/skills`, `GET /api/agents/{id}/skills`, `DELETE` both scopes, `POST /api/skills/upload` (ZIP, top-dir strip, `SKILL.md` enforced, zip-slip guarded)
- **File upload**: `POST /api/agents/{id}/files` (multipart, session-scoped) and the workspace file list/content endpoints backing the Workspace panel

## V0.4 Features

- **Docker sandbox**: per-agent long-lived container (lazy create, reuse, stale cleanup), agent workspace bind-mounted at `/workspace`
- **`exec` tool**: shell commands run only inside the container — no host shell path exists; double-gated by `OPENAGENT_SANDBOX_DOCKER_ENABLED` and the per-agent tool toggle (both default off)
- **Resource limits**: configurable CPU / memory / network (defaults `1` / `512m` / `bridge`)
- **Exit-code semantics**: non-zero exits return as `... Exit code: N` observations so the model can react and iterate

## V0.3 Features

- **Context compaction**: two-stage prune/summarize with tool-call pairing protection, history logging to `memory/logs/`, and safe cutoff so the model request never starts with a dangling `role=tool` message
- **Memory files**: `MEMORY.md` / `USER.md` / `HISTORY.md` at the agent level, injected into the system prompt every turn
- **`memory_search` tool**: search the agent's long-term memory files for facts and preferences (enabled by default)
- **Auto-persist memory**: every N user turns the model extracts facts/preferences from recent conversation and appends them to the memory files
- **Write-time threat scanning**: prompt-injection, credential-leak, and SSH-backdoor patterns are logged and still written (fastclaw-compatible fail-open behavior)
- **Memory API**: `GET /api/agents/{agentId}/memory` and `PUT /api/agents/{agentId}/memory`

## V2 Features

- Spring Boot 3.5 and Java 17 modular backend
- Automatic Flyway database initialization
- SQLite by default, with PostgreSQL driver support
- Fixed local identity (`local-user`) and no permission checks
- Seeded default Provider, Agent, and per-agent tool configuration
- OpenAI-compatible streaming chat **with standard Tool Calling** (streamed `tool_calls` fragment aggregation, multi-tool responses, `role=tool` result round-trip)
- **ReAct agent loop**: multi-round `model → tool → model` with loop protection (3 identical calls), all-failed-rounds tool disabling, iteration cap with forced final synthesis, and per-run/per-tool timeouts
- **Built-in workspace tools**: `list_dir`, `read_file` (enabled by default); `write_file`, `edit_file`, `apply_patch`, `web_fetch` (disabled by default, opt-in per agent)
- **Security boundaries**: per-session workspace isolation, path-traversal/symlink-escape rejection, binary-file read refusal, SSRF-guarded web fetch (loopback/private/metadata addresses blocked, per-hop redirect re-validation)
- Persisted sessions, messages, tool executions, agent runs, and replayable final events (`tool_call` / `tool_result` / `content` / `error` / `done`)
- Browser disconnect does not cancel a running agent turn; reconnect replays from any `seq` cursor
- Existing frontend opens directly at `/agents/default/chat/` and renders tool calls as collapsible groups

The administration backend, MCP, scheduler, channels, host shell/exec, and production authentication are deferred to later versions. Because authentication is bypassed, the default bind address remains `127.0.0.1`.

## Modules

| Module | Responsibility |
|---|---|
| `framework` | Shared conventions, error codes, and global exception mapping |
| `infra-ai` | OpenAI-compatible model client: streaming, tool-call aggregation, orphan tool-call stripping |
| `agent-core` | ReAct agent kernel, tool framework ports, run state machine (no Spring dependency) |
| `bootstrap` | Spring Boot APIs, agent run coordinator, tool registry/invoker, built-in tools, persistence |
| `frontend` | Reused and rebranded Next.js frontend |

## Prerequisites

- JDK 17
- Node.js 20+ and pnpm for rebuilding the frontend

The Maven Wrapper uses Maven 3.9.11. Dependencies are cached under `.m2/repository`, and the Maven distribution is cached under `.maven-user`.

## Configure A Model

The only required model setting is an API key. Defaults target the OpenAI-compatible Chat Completions API.

```powershell
$env:OPENAGENT_MODEL_API_KEY = "your-api-key"
```

For another OpenAI-compatible provider:

```powershell
$env:OPENAGENT_MODEL_PROVIDER = "openai-compatible"
$env:OPENAGENT_MODEL_API_BASE = "https://your-provider.example/v1"
$env:OPENAGENT_MODEL_API_KEY = "your-api-key"
$env:OPENAGENT_MODEL = "your-model"
```

Supported settings:

| Variable | Default |
|---|---|
| `OPENAGENT_BIND` | `127.0.0.1` |
| `OPENAGENT_PORT` | `18953` |
| `OPENAGENT_DATABASE_URL` | `jdbc:sqlite:openagent.db` |
| `OPENAGENT_DATABASE_USERNAME` | empty |
| `OPENAGENT_DATABASE_PASSWORD` | empty |
| `OPENAGENT_DATABASE_POOL_SIZE` | `1` |
| `OPENAGENT_MODEL_PROVIDER` | `openai` |
| `OPENAGENT_MODEL_API_BASE` | `https://api.openai.com/v1` |
| `OPENAGENT_MODEL_API_KEY` | empty |
| `OPENAGENT_MODEL` | `gpt-4.1-mini` |
| `OPENAGENT_MODEL_TEMPERATURE` | `0.7` |
| `OPENAGENT_MODEL_MAX_TOKENS` | `2048` |
| `OPENAGENT_SYSTEM_PROMPT` | built-in OpenAgent assistant prompt |

Note: `OPENAGENT_MODEL` / `OPENAGENT_SYSTEM_PROMPT` seed the default agent on first boot only. After that, the default agent's model is managed via `PUT /api/agents/default` (or the UI), and restarts no longer overwrite it. Provider connection settings (`OPENAGENT_MODEL_PROVIDER` / `API_BASE` / `API_KEY`) are still refreshed from the environment on every boot.

Agent loop and tool settings:

| Variable | Default | Notes |
|---|---|---|
| `OPENAGENT_AGENT_MAX_TOOL_ITERATIONS` | `8` | Allowed range 1-20 |
| `OPENAGENT_AGENT_RUN_TIMEOUT` | `10m` | Whole-run deadline |
| `OPENAGENT_TOOL_TIMEOUT` | `30s` | Per-tool-execution deadline |
| `OPENAGENT_TOOL_MAX_RESULT_CHARS` | `65536` | Tool results are truncated beyond this |
| `OPENAGENT_WORKSPACE_ROOT` | `./workspace` | Session dirs at `<root>/<agentId>/sessions/<sessionId>` |
| `OPENAGENT_READ_FILE_MAX_BYTES` | `1048576` | `read_file` single-file limit |
| `OPENAGENT_WEB_FETCH_ENABLED` | `false` | Global gate on top of the per-agent toggle |
| `OPENAGENT_WEB_FETCH_MAX_BYTES` | `1048576` | Response size cap |
| `OPENAGENT_WEB_SEARCH_ORDER` | `searxng` | web_search provider fallback chain |
| `OPENAGENT_WEB_SEARCH_SEARXNG_ENDPOINT` | empty | SearXNG instance URL (e.g. `http://127.0.0.1:8888`); empty hides web_search |
| `OPENAGENT_CONTEXT_TOKEN_THRESHOLD` | `80000` | Trigger context compaction when estimated tokens exceed this |
| `OPENAGENT_CONTEXT_PRUNE_TURN_AGE` | `20` | Number of recent turns kept verbatim during compaction |
| `OPENAGENT_CONTEXT_SUMMARY_MAX_TOKENS` | `2048` | Max tokens for compaction summarization call |
| `OPENAGENT_MEMORY_ENABLED` | `true` | Inject memory files into the system prompt |
| `OPENAGENT_MEMORY_AUTO_PERSIST_ENABLED` | `true` | Enable automatic memory extraction |
| `OPENAGENT_MEMORY_AUTO_PERSIST_INTERVAL` | `5` | User-message count modulo that triggers auto-persist |
| `OPENAGENT_MEMORY_MAX_FILE_CHARS` | `32768` | Max chars for MEMORY.md / USER.md |
| `OPENAGENT_SKILLS_DIR` | `./skills` | Global skills directory (per-agent skills live at `<workspace>/<agentId>/skills`) |
| `OPENAGENT_WORKSPACE_HISTORY_ENABLED` | `true` | Snapshot the session workspace to a git bare repo after every run (`<workspace>/.history/`) |
| `OPENAGENT_SANDBOX_DOCKER_ENABLED` | `false` | Global gate for the `exec` tool |
| `OPENAGENT_SANDBOX_IMAGE` | `python:3.12-slim` | Sandbox container image |
| `OPENAGENT_SANDBOX_CPUS` | `1` | Container CPU limit |
| `OPENAGENT_SANDBOX_MEMORY` | `512m` | Container memory limit |
| `OPENAGENT_SANDBOX_NETWORK` | `bridge` | Container network mode (`none` to lock down) |

Without an API key, the UI and database still start normally; sending a message returns a clear streaming configuration error.

## Built-in Tools

| Tool | Default | Risk |
|---|---|---|
| `list_dir` | enabled | read-only |
| `read_file` | enabled | read-only, 1 MiB limit, binary files refused |
| `write_file` | disabled | writes inside the session workspace |
| `edit_file` | disabled | exact-substring replacement with uniqueness check |
| `apply_patch` | disabled | multi-file Codex-DSL patch, atomic (no partial writes) |
| `web_fetch` | disabled | http/https only, SSRF-guarded, double-gated by `OPENAGENT_WEB_FETCH_ENABLED` |
| `web_search` | disabled | provider chain (built-in SearXNG; brave/exa pluggable), needs `OPENAGENT_WEB_SEARCH_SEARXNG_ENDPOINT` |
| `memory_search` | enabled | text search over `MEMORY.md` / `USER.md` / `HISTORY.md` |
| `exec` | disabled | shell commands inside the Docker sandbox only; needs `OPENAGENT_SANDBOX_DOCKER_ENABLED=true` |
| `load_skill` | enabled | loads full instructions of an installed skill by name |

Per-agent enablement lives in the `agent_tools` table (seeded on startup, user overrides preserved across restarts). All file tools are confined to the per-session workspace directory — absolute paths, `..` traversal, and symlink escapes are rejected. `memory_search` reads agent-level memory files, not the session workspace.

## Build And Run

Build the reused frontend first so it is included in the executable Spring Boot jar:

```powershell
Set-Location frontend
pnpm install --frozen-lockfile
pnpm build
Set-Location ..
```

Then test and start OpenAgent:

```powershell
$env:JAVA_HOME = "D:\software\Java\java17"
$env:MAVEN_USER_HOME = "$PWD\.maven-user"
.\mvnw.cmd test
.\mvnw.cmd -pl bootstrap -am package -DskipTests
java -jar bootstrap\target\bootstrap-0.1.0-SNAPSHOT.jar
```

Open [http://127.0.0.1:18953](http://127.0.0.1:18953). The root page identifies the fixed local user and redirects directly to the default chatbot.

Useful API checks:

- `http://127.0.0.1:18953/healthz`
- `http://127.0.0.1:18953/api/status`
- `http://127.0.0.1:18953/api/me`
- `http://127.0.0.1:18953/api/agents`

## Try The Agent Loop

1. Drop a file into the session workspace (create the directory on first use): `workspace/default/sessions/<your-session-id>/README.md`
2. In the chat page, ask: *"List the files in your workspace, then read README.md and summarize it."*
3. Watch the model call `list_dir`, then `read_file`, then answer from real file content — each step renders as a collapsible tool group.
4. Switch to another conversation mid-run and come back: the run keeps going server-side and the finished result is there.
5. Ask it to read a path outside the workspace (e.g. `../../../openagent.db`) to see the security boundary respond with `WORKSPACE_PATH_FORBIDDEN`.
6. Inspect the run trail in the database: `agent_runs` (one row per turn, terminal status + iteration count) and `tool_executions` (one row per tool call with timing and result).

Implementation plans: [OPENAGENT_JAVA_V1_PLAN.md](docs/OPENAGENT_JAVA_V1_PLAN.md), [OPENAGENT_JAVA_V2_PLAN.md](docs/OPENAGENT_JAVA_V2_PLAN.md), [OPENAGENT_JAVA_V3_PLAN.md](docs/OPENAGENT_JAVA_V3_PLAN.md), [OPENAGENT_JAVA_V4_PLAN.md](docs/OPENAGENT_JAVA_V4_PLAN.md), [OPENAGENT_JAVA_V5_PLAN.md](docs/OPENAGENT_JAVA_V5_PLAN.md), [OPENAGENT_JAVA_V6_PLAN.md](docs/OPENAGENT_JAVA_V6_PLAN.md).
