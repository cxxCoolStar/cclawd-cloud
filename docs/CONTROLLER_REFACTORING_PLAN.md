# Controller 层规范化改造计划（参考 ragent 风格）

> 参考项目：`D:\resources\code\ragent`（com.nageoffer.ai.ragent）
> 目标项目：`D:\resources\code\cclawd-cloud`（ai.openagent）
> 状态：待评审

## 一、现状问题

当前 `bootstrap/api` 下 6 个 Controller 存在以下不规范之处（对照 ragent 风格）：

| # | 问题 | 现状举例 | ragent 做法 |
|---|------|---------|------------|
| 1 | 无统一返回结构 | `ChatController.history()` 直接返回 `Map<String, Object>` | 所有接口返回 `Result<T>`（code/message/data/requestId），经 `Results.success()` 构建 |
| 2 | `ApiResult` 已定义但零使用 | `framework/convention/ApiResult.java` 是死代码 | `Result` + `Results` 是全站唯一出口 |
| 3 | 无错误码体系 | 直接抛 `ResponseStatusException(HttpStatus.NOT_FOUND, ...)` | `IErrorCode` / `BaseErrorCode`（阿里巴巴 A/B/C 类错误码）+ `ClientException` / `ServiceException` / `RemoteException` |
| 4 | 无全局异常处理器 | 异常由 Spring 默认机制处理，格式不可控 | `@RestControllerAdvice GlobalExceptionHandler` 统一拦截、打日志、转 `Result` |
| 5 | 无 Request/VO DTO | 响应用 `Map.of(...)` 手工拼装；请求用 Controller 内嵌 record | 每个业务域下 `controller/request/*Request.java`、`controller/vo/*VO.java` |
| 6 | Controller 承担了组装/协议逻辑 | `AgentController.toResponse()`、`ChatController.SseWriter` 内部类 | Controller 只做「收参 → 调 Service → 包装返回」三件事，SSE 协议封装下沉 framework（`SseEmitterSender`） |
| 7 | 包结构扁平 | 所有 Controller 挤在 `bootstrap.api` | 按业务域垂直分包：`{domain}/controller`、`{domain}/service`、`{domain}/dao` |
| 8 | Controller 直接访问持久层 | `AgentController` / `ChatController` 直接注入 `OpenAgentStore` | Controller 只依赖 Service 接口，Service 接口 + `impl` 分离 |
| 9 | 手写构造器注入 | 每个 Controller 手写 4~5 行构造器 | Lombok `@RequiredArgsConstructor` |
| 10 | 无接口注释 | 方法无 Javadoc | 类与方法均有中文 Javadoc（类：`XX控制器 + 一句话职责`；方法：一句话动作描述） |

## 二、目标风格速览（摘自 ragent）

```java
/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return Results.success(knowledgeBaseService.create(requestParam));
    }
}
```

要点：
- 类上仅 `@RestController + @RequiredArgsConstructor`，不用类级 `@RequestMapping`，每个方法写完整路径
- 请求参数统一命名 `requestParam`，类型为独立的 `XxxRequest`
- 返回 `Result<T>`，`T` 为 `XxxVO` / `String`(id) / `Void`
- 路径变量用 kebab-case（如 `{kb-id}`）
- SSE / 流式接口例外：直接返回 `SseEmitter`，不套 `Result`

## 三、改造方案

### Phase 0：决策项（开工前确认）

1. **是否引入 Lombok**：ragent 全面使用（`@Data`、`@RequiredArgsConstructor`、`@Slf4j`）。本项目目前零依赖。
   - 建议：引入，与参考风格保持一致；根 pom 加 `lombok` + `lombok.config`。
   - 若不引入：DTO 用 record，注入保持手写构造器，其余计划不变。
2. **URL 前缀**：ragent 无 `/api` 前缀，本项目前端 `frontend/src/lib/api.ts` 全部按 `/api/**` 调用。
   - 建议：**保留 `/api` 前缀**，避免前端大改；仅统一响应结构。
3. **响应结构变更对前端的影响**：包一层 `Result` 后，`{ "agents": [...] }` 会变为 `{ "code": "0", "data": { ... } }`。
   - 前端 `api.ts` 的 fetch 封装需同步适配（在 `request()` 里统一解包 `data` 并检查 `code`）。
   - 本计划将前后端同一 PR 内完成，避免中间态。

### Phase 1：framework 模块打底（无破坏性，纯新增）

在 `framework/src/main/java/ai/openagent/framework/` 下新增，参考 ragent framework 模块：

```
framework/
├── convention/
│   └── Result.java              # 替代 ApiResult：code/message/data/requestId + isSuccess()
├── errorcode/
│   ├── IErrorCode.java          # code() + message() 接口
│   └── BaseErrorCode.java       # A/B/C 三类基础错误码枚举
├── exception/
│   ├── AbstractException.java   # errorCode + errorMessage
│   ├── ClientException.java     # A 类：客户端错误
│   ├── ServiceException.java    # B 类：服务端错误
│   └── RemoteException.java     # C 类：三方调用错误
└── web/
    ├── Results.java             # success()/success(data)/failure(...) 静态构造器
    ├── GlobalExceptionHandler.java  # @RestControllerAdvice，拦截参数校验/业务/兜底异常
    └── SseEmitterSender.java    # SSE 发送封装（吸收 ChatController.SseWriter）
```

- 删除死代码 `convention/ApiResult.java`
- `GlobalExceptionHandler` 拦截：`MethodArgumentNotValidException`、`AbstractException`、`Throwable` 兜底（本项目暂无 Sa-Token / 文件上传，对应 handler 不搬）
- 日志格式沿用 ragent：`log.error("[{}] {} [ex] {}", method, url, ...)`

### Phase 2：bootstrap 按业务域重新分包

现状 `bootstrap.api` 扁平包 → 拆为垂直业务域（对齐 ragent 的 `user/`、`knowledge/`、`rag/`）：

```
bootstrap/src/main/java/ai/openagent/bootstrap/
├── agent/
│   ├── controller/AgentController.java
│   ├── controller/vo/AgentVO.java
│   └── service/AgentService.java + service/impl/AgentServiceImpl.java
├── chat/
│   ├── controller/ChatController.java
│   ├── controller/request/ChatStreamRequest.java
│   ├── controller/vo/ChatMessageVO.java、ChatSessionVO.java、ChatHistoryVO.java
│   └── service/…（现有 ChatService/ChatEventHub/网关类归入此域）
├── identity/
│   ├── controller/LocalIdentityController.java
│   └── controller/vo/CurrentUserVO.java
├── status/
│   ├── controller/StatusController.java、HealthController.java
│   ├── controller/vo/PlatformStatusVO.java
│   └── service/…（现有 status 包内容）
└── web/
    └── FrontendController.java   # 纯页面 forward，不属于业务域
```

### Phase 3：逐 Controller 改造

按风险从低到高的顺序：

| 顺序 | Controller | 改造内容 | 备注 |
|-----|-----------|---------|------|
| 1 | `StatusController` | `Map` → `Result<PlatformStatusVO>`；`PlatformStatusService.currentStatus()` 返回 VO | `PlatformCapabilities` 已是 record，直接映射 |
| 2 | `LocalIdentityController` | `me()` → `Result<CurrentUserVO>`；`logout()` → `Result<Void>` | 对齐 ragent `UserController.currentUser()` |
| 3 | `AgentController` | 新建 `AgentService`（Controller 不再碰 `OpenAgentStore`）；`toResponse()` 移入 service 层做 VO 装配；`ResponseStatusException` → `ClientException(AGENT_NOT_FOUND)` | 错误码入 `BaseErrorCode` 或域内 errorcode |
| 4 | `ChatController` | `history`/`sessions`/`todo` → `Result<T>` + VO；内嵌 `record ChatRequest` → `controller/request/ChatStreamRequest`；`SseWriter` 内部类 → framework `SseEmitterSender`；`subscribe`/`stream` 保持返回 `SseEmitter`/`StreamingResponseBody`（SSE 不套 Result，对齐 ragent `RAGChatController`） | 最复杂，涉及流式协议 |
| 5 | `HealthController` | **不改造**：`/healthz` 等探针必须返回纯文本 `ok`，K8s 兼容 | 加注释说明豁免原因 |
| 6 | `FrontendController` | **不改造**：页面 forward，非 REST API | 移包即可 |

每个 Controller 统一套用：
- `@RestController + @RequiredArgsConstructor`（或手写构造器，视 Phase 0 决策）
- 类 Javadoc：`XX控制器` + 一句话职责；方法 Javadoc：一句话动作
- 请求体参数统一命名 `requestParam`

### Phase 4：前端适配（同 PR）

`frontend/src/lib/api.ts`：
- 在统一 fetch 封装中解包 `Result`：`code !== "0"` 时抛错（携带 `message`），成功时返回 `data`
- 调用点由 `resp.agents` 之类改为解包后的 VO 结构
- SSE 相关调用（subscribe/stream）不受影响

### Phase 5：测试与守护

1. 更新现有测试断言：`ChatFlowTest`、`PlatformEndpointsTest` 改为断言 `Result` 结构
2. `ModuleArchitectureTest` 新增 ArchUnit 规则，把风格固化为可执行约束：
   - `..controller..` 中的类必须以 `Controller` 结尾，且带 `@RestController`/`@Controller`
   - Controller 不得依赖 `..persistence..`（强制走 Service）
   - Controller 公有方法返回类型必须是 `Result` / `SseEmitter` / `ResponseEntity`（Health/Frontend 豁免）
   - `..controller.request..` 类以 `Request` 结尾、`..controller.vo..` 类以 `VO` 结尾
3. 全量 `mvnw verify` + 前端 `pnpm build`，手动过一遍 chat 流式对话（SSE 回归重点）

## 四、不采纳的 ragent 元素（及原因）

| ragent 元素 | 不采纳原因 |
|------------|-----------|
| Sa-Token（`StpUtil.checkRole`） | 本项目当前是 local 单用户模式，无认证需求；预留 `identity` 域，后续接入时再引入 |
| MyBatis-Plus `IPage` 分页 | 本项目持久层是自研 `OpenAgentStore`（SQLite），分页需求出现时再定义自己的 `PageResponse` |
| `@IdempotentSubmit` 幂等切面 | 依赖 Redis，当前无此基础设施；chat stream 的并发控制已由 `ChatService.beginTurn` 保证 |
| RocketMQ / 分布式 ID 等 framework 组件 | 与 Controller 规范无关，超出本次范围 |
| Apache License 文件头 | 本项目未采用该 License 头风格 |

## 五、实施顺序与工作量估计

```
PR-1  Phase 1（framework 打底）+ Phase 5.2（ArchUnit 规则先行，标记 @Disabled）   ~0.5 天
PR-2  Phase 2 + Phase 3（Controller 改造）+ Phase 4（前端适配）+ 测试更新，启用规则  ~1.5 天
```

两个 PR 均保持 `mvnw verify` 绿色可合并。
