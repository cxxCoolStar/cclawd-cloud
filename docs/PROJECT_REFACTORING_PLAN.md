# cclawd-cloud 全项目规范化改造计划（参考 ragent 风格）

> 参考项目：`D:\resources\code\ragent`（com.nageoffer.ai.ragent，Java 17 + Spring Boot 3.5.7，多模块 Maven）
> 目标项目：`D:\resources\code\cclawd-cloud`（ai.openagent，同为 Java 17 + Spring Boot 3.5.7 多模块 + Next.js 前端）
> 关联文档：`docs/CONTROLLER_REFACTORING_PLAN.md`（Controller 层专项计划，作为本计划 Phase 2/3 的细化）
> 状态：待评审

---

## 一、现状诊断总览

### 1.1 模块层面：架构是"计划"，不是"现实"

| 模块 | 现状 | 问题 |
|---|---|---|
| `framework` | 仅 `ApiResult`、`RequestIdentity` 两个类 | 两者均零引用（死代码），未起到 ragent framework 的"公共基座"作用 |
| `infra-ai` | 8 个接口/模型类 | bootstrap 零 import，空壳 |
| `agent-core` | AgentKernel + 13 个 tool 类 | bootstrap 零 import，空壳 |
| `runtime-integration` | 仅 package-info | 占位 |
| `cli` | 只有 pom，无源码 | 空模块 |
| `bootstrap` | 全部业务（约 1200 行） | 所有逻辑塌缩于此，分层只存在于 pom 依赖与 ArchUnit 规则中 |

对照：ragent 的 framework（统一返回/异常/错误码/全局处理器/SSE 封装/上下文）与 infra-ai（多厂商 LLM 客户端抽象）都是**被 bootstrap 真实消费**的模块。

### 1.2 代码层面核心缺失（对照 ragent）

| # | 维度 | cclawd-cloud 现状 | ragent 做法 |
|---|------|------------------|------------|
| 1 | 统一返回 | 所有接口手拼 `Map<String,Object>`；`ApiResult` 死代码 | `Result<T>`（code/message/data/requestId）+ `Results.success()` 全站唯一出口 |
| 2 | 错误码/异常 | 到处直接抛 `ResponseStatusException`（含非 Web 组件 `ChatTurnCoordinator.java:32,44`） | `IErrorCode`/`BaseErrorCode`（阿里 A/B/C 类）+ `ClientException`/`ServiceException`/`RemoteException` 三层异常 |
| 3 | 全局异常处理 | 无 `@RestControllerAdvice` | `GlobalExceptionHandler` 统一拦截、打日志、转 `Result` |
| 4 | 日志 | **后端 main 代码零日志**（grep Logger/@Slf4j 无匹配） | `@Slf4j` + 中文结构化日志（81 个文件） |
| 5 | DTO/VO | 请求为 Controller 内嵌 record；响应全为 `Map.of(...)`；引了 validation 依赖但零注解 | `controller/request/XxxRequest` + `controller/vo/XxxVO` + `service/bo/XxxBO`，参数校验交给注解 + 全局处理器 |
| 6 | 分包 | `api/` 扁平堆 6 个 Controller；`chat/` 包混装 Service/基础设施/网关/配置类 | 业务域垂直分包（package-by-feature），域内 `controller/service[impl]/dao/dto/enums/config` |
| 7 | 分层 | `AgentController`、`ChatController` 直接注入 `OpenAgentStore` | Controller 只依赖 Service 接口 |
| 8 | 依赖注入 | 手写构造器（风格统一但样板多）；配置注入 `@Value` 与 `@ConfigurationProperties` 并存 | Lombok `@RequiredArgsConstructor` + `private final`；配置统一 `XxxProperties` |
| 9 | 线程池/并发 | `ChatExecutionConfiguration.java:15-19` 参数硬编码；`OpenAgentStore` 用 `synchronized + @Transactional` 混搭保护 seq 竞态 | 集中式 `ThreadPoolExecutorConfig`：命名线程池 + 显式拒绝策略 + 参数可配置 |
| 10 | 事件模型 | 全链路 `Map<String,Object>` 弱类型（ChatService → EventHub → Controller） | 强类型契约对象放 framework `convention/`（ChatMessage 等） |
| 11 | Javadoc | 后端几乎无注释 | 类/方法/字段全中文 Javadoc |
| 12 | 测试 | 后端 4 个测试约 200 行；前端零测试 | 单测 `XxxTest` + 集成 `XxxTests` 镜像主包，回归测试标注历史缺陷编号 |

### 1.3 具体坏味道清单（改造时逐项消除）

1. **【P0·阻塞】工作区编译不通过**：`chat/ChatService.java` 未提交修改删除了 `EventConsumer consumer` 参数，但 `publishTransient`（89 行）与 `publishPersistent`（102 行）仍引用 `consumer`。必须先修复。
2. 重复代码：`ChatEventHub.key()`(44-46) 与 `ChatTurnCoordinator.key()`(52-54) 逐字相同；`OpenAgentStore.listAgents/findAgent` RowMapper 重复（86-118）；`ChatController.subscribe/stream` 两段 SSE 泵循环高度相似（81-131）。
3. 过长方法：`OpenAiCompatibleChatModelGateway.stream()` 71 行（HTTP 构建 + SSE 解析 + 错误处理糅合）；`OpenAgentStore.seedDefaults()` 60 行。
4. 硬编码：线程池参数、SSE 心跳 15s/轮询 1s（`ChatController.java:90,116`）、网关超时 10min/20s（`OpenAiCompatibleChatModelGateway.java:59,66`）、`LocalIdentityController.java:14-26` 整个用户对象写死（与 `OpenAgentStore.seedDefaults` 重复维护）、`AgentController.java:43-49` 假字段、`ChatController.todo()` 空壳响应。
5. 资源问题：`OpenAiCompatibleChatModelGateway.java:65-67` 每次请求 new `HttpClient`，应注入复用。
6. 异常吞噬：`ChatController.java:102,128` `catch (IOException ignored)` 无日志静默吞掉。
7. `OpenAgentStore` 六表混一（294 行），兼管种子数据；`LOCAL_USER_ID` 在 Controller 层 6 处直引。
8. 仓库卫生：`openagent.db`、`openagent-server*.log`、`.m2/`、`.maven-user/` 等运行产物/本地目录在仓库内。
9. 同名类冲突隐患：`ai.openagent.agent.tool.ToolCall` 与 `ai.openagent.infra.ai.model.ToolCall`。
10. 前端：`components/chat-screen.tsx` 4134 行 God Component；`lib/api.ts` 2029 行 / 104 个函数手写 fetch，无统一请求层；前端调用 21 类端点而后端仅实现 13 个 mapping（前后端脱节）。

---

## 二、目标风格基线（从 ragent 提炼的可复用模板）

改造完成后，本项目应满足以下"风格宪法"（后续用 ArchUnit 固化）：

1. **模块**：framework = 横切基座（convention/errorcode/exception/web），infra-ai = AI 能力层，bootstrap = 唯一业务应用；模块要么被真实消费，要么删除。
2. **分包**：bootstrap 按业务域垂直切分，域内结构统一：

   ```
   <domain>/
   ├── controller/            # 仅「收参 → 调 Service → Results.success()」
   │   ├── request/XxxRequest
   │   └── vo/XxxVO
   ├── service/XxxService     # 接口
   │   └── impl/XxxServiceImpl
   ├── dao/（或本项目的 repository/）
   ├── dto/  enums/  config/  constant/
   ```

3. **Controller 规约**：类上仅 `@RestController + @RequiredArgsConstructor`；方法写完整路径；请求体形参统一命名 `requestParam`；返回 `Result<T>`；SSE 接口豁免（直接返回 SseEmitter/StreamingResponseBody）；类与方法必有中文 Javadoc。
4. **异常规约**：业务代码只抛 `ClientException/ServiceException/RemoteException`（配错误码），Web 层异常类型不出现在 service 及以下；断言风格 `Assert.notBlank(x, () -> new ClientException(...))`（引 Hutool 或自写轻量 Assert）。
5. **配置规约**：一切可调参数走 `@ConfigurationProperties` 的 `XxxProperties` 类（每字段带 Javadoc），杜绝 `@Value` 与硬编码；密钥用 `${ENV_VAR:}` 占位（现有 yml 已符合）。
6. **日志规约**：全面 `@Slf4j`，中文消息 + 占位符 + 结构化前缀，如 `log.info("[chat] 开始流式回合，agentId={}, sessionId={}", ...)`；catch 块禁止无日志吞异常。
7. **并发规约**：线程池集中在一个 `ThreadPoolExecutorConfig`，命名前缀 + 显式拒绝策略 + 参数来自 Properties。
8. **强类型规约**：跨层传输不使用 `Map<String,Object>`；聊天事件定义为密封的事件类型体系。
9. **测试规约**：单测 `XxxTest`（无 Spring）/集成 `XxxTests`（@SpringBootTest），包路径镜像主代码。

---

## 三、分阶段改造计划

### Phase 0：止血与决策（半天内）

**P0-1 修复编译错误**（最优先）：补全 `ChatService.publishTransient/publishPersistent` 中对已删除 `consumer` 的引用，让未提交的 `ChatTurnCoordinator` 重构闭环，`mvnw verify` 恢复绿色后再谈规范化。

**P0-2 仓库卫生**：`.gitignore` 补 `*.db`、`*.log`、`.m2/`、`.maven-user/`，并从 git 移除已跟踪的运行产物。

**P0-3 决策项**（开工前确认，与 CONTROLLER_REFACTORING_PLAN Phase 0 合并）：

| 决策 | 建议 |
|---|---|
| 是否引入 Lombok | **引入**（@RequiredArgsConstructor/@Slf4j/@Data/@Builder），与 ragent 一致；根 pom 全局依赖 + `lombok.config`（`addLombokGeneratedAnnotation=true`、`equalsAndHashCode.callSuper=skip`） |
| URL 前缀 | **保留 `/api`**，避免前端大改 |
| `Result` 包装对前端的影响 | 前端 `api.ts` 统一解包，前后端同 PR 完成 |
| 空壳模块处置 | **保留 `infra-ai`（Phase 4 消费它），删除或归档 `cli`、`runtime-integration`**；`agent-core` 若近期无集成计划，从 reactor 中暂时移除，避免"假架构" |
| 是否引入 Spotless License 头 | 不引入（本项目无该 License 风格），但可引入 Spotless 仅做格式化（可选） |

### Phase 1：framework 打底（纯新增，无破坏性，~0.5 天）

照搬 ragent framework 的最小必要子集到 `ai.openagent.framework`：

```
framework/
├── convention/
│   ├── Result.java                  # code/message/data/requestId + isSuccess()；删除 ApiResult
│   └── event/                       # 聊天事件强类型契约（替代 Map 事件），供 bootstrap 与前端协议共用
├── errorcode/
│   ├── IErrorCode.java              # code() + message()
│   └── BaseErrorCode.java           # 阿里规范 A/B/C 类基础错误码
├── exception/
│   ├── AbstractException.java
│   ├── ClientException.java / ServiceException.java / RemoteException.java
└── web/
    ├── Results.java                 # success()/success(data)；failure 包私有仅供 GlobalExceptionHandler
    ├── GlobalExceptionHandler.java  # @RestControllerAdvice：参数校验 / AbstractException / Throwable 兜底
    └── SseEmitterSender.java        # 线程安全 SSE 封装（CAS 幂等关闭），吸收 ChatController.SseWriter
```

同时删除死代码：`ApiResult`、`RequestIdentity`（后者若 identity 域后续要用则移入并真实消费）。

### Phase 2：bootstrap 业务域分包 + Controller/Service 规范化（~1.5 天）

即 `CONTROLLER_REFACTORING_PLAN.md` 的 Phase 2/3/4，此处只列增量要求：

```
bootstrap/src/main/java/ai/openagent/bootstrap/
├── agent/      controller[/vo] + service[/impl] + repository
├── chat/       controller[/request|/vo] + service[/impl] + event/ + gateway/ + config/
├── identity/   controller[/vo] + service
├── status/     controller[/vo] + service
└── web/        FrontendController（forward，豁免）；HealthController（探针，豁免）
```

增量要求（超出原 Controller 计划的部分）：

1. **Service 层补齐**：新建 `AgentService`、`IdentityService`，Controller 一律不再注入 `OpenAgentStore`；VO 装配下沉 service。
2. **异常下沉修正**：`ChatTurnCoordinator` 的 409 冲突改抛 `ClientException(CHAT_TURN_CONFLICT)`，`ChatService.beginTurn` 的手写 if 校验改为 Request 上的 `@NotBlank` 等注解（validation 依赖已在，只是没用）。
3. **消除重复**：`ChatEventHub.key()`/`ChatTurnCoordinator.key()` 抽为 `ChatSessionKey`（record，value object）；`subscribe/stream` 的 SSE 泵循环抽公共方法/组件。
4. **`LocalIdentityController` 硬编码用户对象**改为读 `OpenAgentStore` 种子数据（单一事实来源），`LOCAL_USER_ID` 收敛到 identity 域一处。
5. **`ChatController.todo()` 空壳接口**：确认前端是否依赖，不依赖则删除，依赖则标注 TODO + 返回 `Result`。

### Phase 3：持久层与并发规范化（~1 天）

1. **拆分 `OpenAgentStore`**：按聚合拆为 `UserRepository`、`AgentRepository`、`ChatSessionRepository`（含 messages/events）等，归入各业务域 `repository/`（或统一 `persistence/` 下按域分包，二选一后用 ArchUnit 固化）；重复 RowMapper 抽常量。
2. **种子数据**从 Store 剥离到独立的 `DataSeeder`（`ApplicationRunner`）。
3. **修正 `synchronized + @Transactional` 混搭**：seq 生成改为 SQL 侧原子方案（如 `INSERT ... SELECT COALESCE(MAX(seq),0)+1` 或独立 seq 表 + 事务内 UPDATE），移除 JVM 锁对事务边界的依赖；至少加注释说明单实例假设。
4. **线程池集中化**：`ChatExecutionConfiguration` 并入 `ThreadPoolExecutorConfig`，参数外置到 `ChatProperties`，线程命名 `chat_turn_executor_` + 显式拒绝策略（参照 ragent `rag/config/ThreadPoolExecutorConfig.java` 的模式，TTL 包装暂不需要——无用户上下文透传需求）。

### Phase 4：网关与事件模型强类型化（~1 天）

1. **`OpenAiCompatibleChatModelGateway` 重构**：
   - `HttpClient` 注入单例复用；超时参数移入 `AiGatewayProperties`；
   - 71 行 `stream()` 拆为 请求构建 / SSE 行解析 / 增量事件回调 三段；
   - **落位到 `infra-ai` 模块**：让 `infra-ai` 的 `ChatClient` 抽象被真实实现与消费（对齐 ragent infra-ai 的定位），bootstrap 只依赖接口。解决 `ToolCall` 同名类冲突（保留 infra-ai 一份）。
2. **事件模型强类型化**：`Map<String,Object>` 事件替换为 framework `convention/event/` 下的密封接口 + record（`MessageDelta`、`TurnStarted`、`TurnCompleted`、`TurnFailed`…），`ChatEventHub`/`ChatService`/SSE 序列化全部改用；前端事件协议字段保持不变（仅后端内部强类型化）。
3. **全面补日志**：`@Slf4j` 铺开，重点：模型调用开始/结束/失败（含耗时）、事件持久化失败、SSE 连接建立/断开、两处 `catch (IOException ignored)` 改为 `log.debug("[sse] 客户端断开: {}", ...)`。

### Phase 5：前端配套（~1 天）

1. **统一请求层**：`lib/api.ts` 抽 `request<T>()` 封装（统一 headers、`Result` 解包、`code !== "0"` 抛带 message 的错误），104 个函数改为薄声明；SSE 调用不受影响。
2. **前后端契约对账**：盘点前端调用的 21 类端点 vs 后端 13 个 mapping，产出三列清单——后端已实现 / 计划实现 / 前端应下线的页面；避免"页面在、接口不存在"的静默 404。
3. **`chat-screen.tsx`（4134 行）拆分**（可独立排期）：按 消息列表 / SSE 消费 hook（`useChatStream`）/ 文件面板 / 预览 / todo 拆为组件 + hooks，目标单文件 < 500 行。
4. 前端补最小测试基建（vitest + 针对 `request()` 解包与错误路径的单测）。

### Phase 6：测试与守护（贯穿各 PR，收口 ~0.5 天）

1. 更新既有测试断言 `Result` 结构（`ChatFlowTest`、`PlatformEndpointsTest`）。
2. 补关键单测：`ChatEventHub` 背压丢弃、`ChatTurnCoordinator` 并发冲突（409→ClientException）、网关 SSE 解析（给定 chunk 流断言事件序列）、Repository 的 seq 原子性。
3. ArchUnit 规则固化（在现有 `ModuleArchitectureTest` 上扩充）：
   - Controller 不得依赖 repository/persistence 包；
   - `..controller..` 公有方法返回 `Result`/`SseEmitter`/`ResponseEntity`（web 包豁免）；
   - `..controller.request..` 以 `Request` 结尾、`..controller.vo..` 以 `VO` 结尾、`..service.impl..` 以 `ServiceImpl` 结尾；
   - service 及以下不得引用 `org.springframework.web.server.ResponseStatusException`；
   - bootstrap 不得直接依赖网关实现类（只依赖 infra-ai 接口）。
4. 每个 PR 保持 `mvnw verify` + `pnpm build` 绿色；chat 流式对话手动回归（SSE 是重点风险面）。

---

## 四、不采纳的 ragent 元素（及原因）

| ragent 元素 | 不采纳原因 |
|---|---|
| Sa-Token 认证 | 当前 local 单用户模式；identity 域预留，接入时再引 |
| MyBatis-Plus + `XxxDO`/`XxxMapper` | 本项目持久层是 JdbcTemplate + SQLite，改 ORM 收益低；仅借鉴其"repository 按聚合划分"思想 |
| RocketMQ / 分布式 ID / 幂等切面 / bizlog 审计 | 依赖 Redis/MQ 基础设施，当前无；单实例内 `ChatTurnCoordinator` 已够 |
| TransmittableThreadLocal 用户上下文 | 单用户模式无跨线程用户态透传需求 |
| Apache License 文件头 + Spotless license 步骤 | 本项目无该 License 风格 |
| Prompt 外置 `.st` 文件 | 当前后端无 prompt 管理需求，agent 能力落地时再引入该模式 |

## 五、实施顺序与工作量估计

```
PR-0  Phase 0（修编译 + 仓库卫生 + 决策确认）                          ~0.5 天
PR-1  Phase 1（framework 打底）+ ArchUnit 规则先行（@Disabled 占位）     ~0.5 天
PR-2  Phase 2（分包 + Controller/Service 规范）+ Phase 5.1（前端解包）    ~1.5 天
PR-3  Phase 3（持久层拆分 + 并发修正 + 线程池集中）                      ~1 天
PR-4  Phase 4（网关落位 infra-ai + 事件强类型 + 补日志）                 ~1 天
PR-5  Phase 6 收口（补测试 + 启用全部 ArchUnit 规则）                    ~0.5 天
—— 可独立排期：Phase 5.2 前后端契约对账、5.3 chat-screen 拆分            ~2 天
```

后端主线约 **5 天 / 6 个 PR**，每个 PR 独立可合并、`mvnw verify` 保持绿色；前端大文件拆分可与后端并行。

## 六、验收标准

- [ ] `mvnw verify` 全绿，ArchUnit 规则全部启用无豁免（web 包除外）
- [ ] 后端 main 代码无 `Map<String,Object>` 作为接口返回值/事件模型
- [ ] 全站无 `ResponseStatusException`（service 及以下），无 `@Value`，无硬编码线程池/超时参数
- [ ] 每个 Controller/Service 类与公有方法有中文 Javadoc
- [ ] grep `catch.*ignored` 无静默吞异常；关键路径（模型调用/持久化/SSE）有日志
- [ ] 前端 `api.ts` 所有调用经统一 `request()`；前后端端点清单对账完成
- [ ] 仓库无运行产物；空壳模块已删除或有明确消费方
