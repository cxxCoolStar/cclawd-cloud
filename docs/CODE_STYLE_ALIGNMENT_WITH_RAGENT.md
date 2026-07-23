# Code Style Alignment With ragent

This document defines how cclawd-cloud should align new backend code with the
ragent engineering style while preserving the OpenAgent platform architecture.

## Decisions

1. Keep the OpenAgent architecture.
   Modules such as `agent-core`, tool execution, skills, evals, and channel
   runtime remain first-class platform concepts.

2. Backend business persistence returns to the ragent style.
   New business tables should use MyBatis-Plus with `DO + Mapper` instead of
   adding more `Record + JdbcTemplate Repository` classes.

3. Frontend structure should stay mostly unchanged.
   The Next.js App Router can remain. Avoid large directory migrations; instead
   split overly large pages into local components, hooks, and service helpers
   when they are touched for feature work.

## Backend Package Shape

New business domains under `bootstrap` should prefer this structure:

```text
bootstrap.<domain>.controller
bootstrap.<domain>.controller.request
bootstrap.<domain>.controller.vo
bootstrap.<domain>.service
bootstrap.<domain>.service.impl
bootstrap.<domain>.dao.entity
bootstrap.<domain>.dao.mapper
```

Examples of domains in this project include `agent`, `chat`, `identity`,
`channel`, `tool`, `skill`, `config`, and `status`.

Infrastructure-only code may keep specialized packages when a domain package
would make the boundary less clear, but the exception should be intentional.

## Controller Style

Normal HTTP APIs should return `Result<T>` and use `Results.success(...)`.

Allowed exceptions:

- SSE and streaming endpoints.
- File upload or download endpoints.
- Redirects or endpoints that must control status codes explicitly.
- Spring Actuator or health integration endpoints.

Avoid returning anonymous `Map` payloads from controllers. Add a `VO` instead,
even for small response envelopes.
Controllers should be thin transport adapters, matching the ragent style:

- A normal query method should usually contain one statement:
  `return Results.success(domainService.query(request));`
- A normal command method should usually contain two statements:
  `domainService.execute(id, request);` followed by `return Results.success();`.
- Passing HTTP parameters to a service and wrapping the service result are controller
  responsibilities. Authorization, validation beyond request binding, default-value
  resolution, persistence access, configuration merging, domain branching, lifecycle
  orchestration, runtime coordination, and VO construction or mapping belong in services.
- Controllers must depend on service interfaces, not service implementations,
  repositories, mappers, database records/DOs, runtime managers, or domain infrastructure.
- Request-to-domain or domain-to-VO conversion should be owned by the service when it
  requires field mapping, collection transforms, masking, or business defaults. A controller
  may pass a request object unchanged or pass simple bound parameters directly.
- Longer methods are allowed only when the HTTP protocol itself requires controller-level
  handling, such as streaming/SSE, multipart transport, redirects, status/header control,
  cookies, or directly copying a file to `HttpServletResponse`. Even in these exceptions,
  domain lookup and business decisions remain in services.

Controller brevity is a design constraint, not merely a formatting preference. If a normal
JSON endpoint needs more than the one-line query or two-line command shape, first extract the
logic into the domain service instead of adding private helper methods to the controller.

## DTO Naming

- Request payloads: `*Request`.
- Response view objects: `*VO`.
- Database entities: `*DO`.
- Internal business transfer objects: `*BO` when needed.

Use Java `record` only for small stable value objects, events, immutable model
messages, or internal tuples. Prefer Lombok POJOs for database entities and
mutable request/view objects that are expected to evolve.

## Persistence Style

New business persistence should use:

```text
bootstrap.<domain>.dao.entity.<Name>DO
bootstrap.<domain>.dao.mapper.<Name>Mapper
```

`DO` classes should use MyBatis-Plus annotations such as `@TableName`,
`@TableId`, `@TableField`, and `@TableLogic` where appropriate.

`Mapper` interfaces should extend `BaseMapper<...DO>`.

Do not add new `bootstrap.persistence.*Repository` classes for ordinary
business tables. Existing JDBC repositories are legacy implementation details
and should be migrated by domain over time.

Migration order should favor simple tables first:

1. `agents`
2. `agent_tools`
3. `agent_mcp_servers`
4. `providers`
5. `configs`
6. `users`
7. `api_keys`

Queue, event, and session tables can be migrated later because they carry more
ordering and concurrency semantics.

## Service Style

Controllers call services; controllers should not call persistence directly.

Services should focus on domain behavior. When a service starts mixing several
responsibilities, split collaborators by reason to change. Common extraction
targets include:

- Access control checks.
- Default value resolution.
- JSON serialization and deserialization of stored configs.
- Lifecycle cleanup orchestration.
- Runtime cache eviction.

Business code should throw framework domain exceptions such as
`ClientException`, `ServiceException`, or `RemoteException`, not web-layer
exceptions.

## Frontend Style

Keep the current Next.js structure. When touching large page files, split
without changing the routing model:

```text
frontend/src/app/<route>/page.tsx
frontend/src/features/<domain>/components
frontend/src/features/<domain>/hooks
frontend/src/features/<domain>/services
```

For small changes, avoid churn. The goal is to stop page files from growing
without forcing a Vite or React Router style migration.

## Guardrails

Architecture tests should block new drift:

- Controller classes must end with `Controller`.
- Request classes must end with `Request`.
- VO classes must end with `VO`.
- Service implementation classes must end with `ServiceImpl`.
- Controllers must not depend on persistence packages directly.
- Services must not depend on web-layer exceptions.
- New DAO entities must end with `DO`.
- New DAO mappers must end with `Mapper` and extend MyBatis-Plus `BaseMapper`.
- New JDBC repositories under `bootstrap.persistence` should not be added for
  normal business tables.

Existing legacy code may be whitelisted while migration is in progress, but new
code should follow these rules by default.
