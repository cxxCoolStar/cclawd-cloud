# OpenAgent

OpenAgent is an open-source, self-hosted AI Agent platform. The current V0.2 milestone provides a Java chat MVP while reusing the existing Next.js frontend.

## V0.2 Features

- Spring Boot 3.5 and Java 17 modular backend
- Automatic Flyway database initialization
- SQLite by default, with PostgreSQL driver support
- Fixed local identity (`local-user`) and no permission checks
- Seeded default Provider and Agent configuration
- OpenAI-compatible streaming chat
- Persisted sessions, messages, and replayable final events
- Existing frontend opens directly at `/agents/default/chat/`

The administration backend, tools, MCP, scheduler, channels, and production authentication are deferred to later versions. Because authentication is bypassed, the default bind address remains `127.0.0.1`.

## Modules

| Module | Responsibility |
|---|---|
| `framework` | Shared conventions and request identity |
| `infra-ai` | Model and provider ports |
| `agent-core` | Agent state, tool ports, and orchestration contracts |
| `runtime-integration` | Runtime adapter contracts |
| `bootstrap` | Spring Boot APIs, chat service, and persistence |
| `cli` | Operator CLI skeleton |
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

Without an API key, the UI and database still start normally; sending a message returns a clear streaming configuration error.

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

The implementation plan is documented in [OPENAGENT_JAVA_V1_PLAN.md](docs/OPENAGENT_JAVA_V1_PLAN.md).