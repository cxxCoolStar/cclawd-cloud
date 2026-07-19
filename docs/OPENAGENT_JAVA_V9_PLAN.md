# OpenAgent Java V9 计划：多用户体系（认证 + RBAC + API Key + 配置继承链）

- 版本：V9（方案稿，编制于 2026-07-18）
- 前置版本：V8（Fallback Chain + 会话队列 + Agent CRUD）
- 关联文档：OPENAGENT_JAVA_REFACTORING_PLAN.md、V1–V8 计划

## 1. 目标与背景

实现 fastclaw 核心能力"一台服务器为多个用户创建多个 agent、相互隔离"，让简历级能力"System/User/Agent 配置继承 + Session 隔离 + RBAC + API Key Scope"成立。

数据地基已就位（V1–V7 有意预留）：`users` 表（含 `role` 列）、`agents.user_id` 列 + `AgentRepository.listByUser`、`sessions` 主键 `(user_id, agent_id, id)`、`configs` 表键空间已带 agent 维度。前端登录/注册/users/apikeys 页面全部已备好，只缺后端。

判断标准：两个注册用户各自登录，各自创建 agent、各自对话，互相看不到对方的 agent/会话/配置；越权访问返回 403/404；API Key 可限定 agent 子集调用 chat 接口。

## 2. 关键摸底结论（开工前已核实）

- 身份硬编码面小且已知：`IdentityConstant.LOCAL_USER_ID` 主代码仅约 8 处使用点（`AgentServiceImpl`、`AgentRunCoordinator`、`ChatServiceImpl`、`IdentityServiceImpl`、`MemoryController`、`PlatformStatusServiceImpl`、`DataSeeder`）。
- 目录隔离天然成立：workspace/memory 路径为 `{workspaceRoot}/{agentId}/`，无 user 维度；因 agentId 全局唯一且有 owner，**只要在 API 层强制 owner 校验，目录级隔离自动成立**，无需改目录结构。
- 无认证设施：没有任何 Filter/Interceptor/Spring Security；`LocalIdentityController` 的 `/api/me`、`/api/logout` 是占位。
- fastclaw 参照：cookie session 认证；角色 `super_admin`/`user`；configs 按 kind/scope_id 寻址，作用域链 system→user→agent；provider 整行替换、setting 字段级合并；API Key 可绑定 agent 子集。
- 前端契约（`frontend/src/lib/api.ts`、`frontend/src/lib/auth.ts`）：POST `/api/register`、`/api/login`、`/api/logout`、GET/PUT `/api/me`、`/api/users` CRUD、`/api/apikeys` CRUD（bearer apikey）。**实施第一步必须先逐字段核对这些契约**。

## 3. 设计

### 3.1 M1：认证与用户管理

- **schema**：新 migration——`users` 表加 `password_hash`（BCrypt）、`display_name` 等缺失列；新建 `auth_sessions` 表（token PK / user_id / created_at / expires_at）。
- **端点**（契约以前端为准）：
  - `POST /api/register`：受 `registration-open` 配置门控（关闭时 403；首个注册用户自动成为 `super_admin` 且不受门控——保证全新部署可引导）。
  - `POST /api/login`：校验密码 → 写 `auth_sessions` → Set-Cookie（HttpOnly, SameSite=Lax）。
  - `POST /api/logout`：删 session 行 + 清 cookie。
  - `GET /api/me`：当前用户；`PUT /api/me`：改 display_name/密码。
- **认证过滤器**：`OncePerRequestFilter`——cookie token → 查 `auth_sessions`（过期剔除）→ 把 userId 放入 `RequestContext`（ThreadLocal，请求结束清理）。未认证请求访问受保护端点 → 401。
- **身份传递改造**：上述 8 处 `LOCAL_USER_ID` 全部改从 `RequestContext` 取；`AgentRunCoordinator` 等异步路径在提交时快照 userId 传入（ThreadLocal 不过线程边界）。
- **迁移兼容**：存量 DB 的 `local-user` 数据保留；若 `users` 表只有种子本地用户且无密码，首次启动引导走"首个注册即 super_admin"。

### 3.2 M2：归属隔离 + RBAC + API Key

- **owner 校验**：所有 `/{agentId}` 路径端点（agents/chat/sessions/memory/workspace/tools/config/skills）统一在 service 入口校验 `agent.userId == 当前用户`（super_admin 豁免）——越权返回 404（不暴露存在性）。这是隔离的核心防线，**每个端点配负向测试**。
- **RBAC**：`role` 列启用。`super_admin`：`/api/users` CRUD（列表/改角色/停用/重置密码）；普通 `user`：仅自助。agent 数量配额（`agentQuota` 语义）本期不做硬限制，留字段。
- **API Key**：新表 `api_keys`（id / user_id / name / key_hash / agent_ids JSON（空=全部）/ created_at / last_used_at）。
  - `POST/GET/DELETE /api/apikeys`（契约以前端为准）；创建时明文只返回一次，库存 hash。
  - 认证过滤器同时接受 `Authorization: Bearer <key>`；key 绑定 agent 子集时，chat/agent 端点校验目标 agent 在 scope 内，否则 403。
  - `shareModelConfig`（chatter 能否继承 owner 模型配置）本期不涉及——chatter 概念随 V10 渠道而来。

### 3.3 M3：配置继承链（system → user → agent 三级）

- `configs` 表加 `scope`/`scope_id` 两列（PK 改为 `(scope, scope_id, config_key)`；存量行迁移为 `scope='system'`）。
- 作用域语义（对齐 fastclaw）：
  - `agents.defaults`、`skills.entries`、`prefs`、`sandbox`：字段级深合并，user 覆盖 system、agent 覆盖 user。
  - provider/model 类：整对象替换（user 配了自己的 provider key 则不再继承 system 的）。
- `ConfigService` 读路径改为合并链 `merged = agent ⊕ user ⊕ system`；写路径按当前用户身份落到对应 scope（super_admin 可写 system）。
- `GET /api/config` 响应形状不变（前端零改动），值为合并结果；打码与打码回写保护逐 scope 生效。
- **明确不做 user-agent 第四级**：chatter 维度随 V10 渠道引入后再评估。

## 4. 里程碑

- **M1（约 1.5 天）**：migration → 认证端点 → 过滤器 + RequestContext → 8 处身份改造 → 测试（注册/登录/登出/过期/401/首用户超管/异步路径身份快照）。
- **M2（约 1.5 天）**：owner 校验铺开 + RBAC + API Key 全链路 → **越权负向测试矩阵**（用户 B 访问用户 A 的 agent 的每类端点均 404；key 越 scope 403；普通用户调 /api/users 403）。
- **M3（约 1 天）**：configs scope 化 + 三级合并 → 测试（字段级合并、provider 整行替换、逐 scope 打码、写隔离——普通用户写不进 system scope）。

## 5. 发布门禁

- `JAVA_HOME='D:\software\Java\java17' ./mvnw verify` 全绿；
- **越权负向测试必须全覆盖**：多用户功能出 bug 是数据泄漏级问题，不是功能问题；
- 手动 smoke：两个浏览器身份各建 agent 对话，交叉访问验证隔离；前端登录/users/apikeys 页走通。

## 6. 明确不交付

- user-agent 第四级配置、chatter 维度（随 V10 评估）
- agentQuota 硬配额、usage 统计
- 多副本/session 外置（`auth_sessions` 在 SQLite，重启不失效；多实例部署仍不支持——维持单机定位）
- OAuth/SSO、密码找回邮件流

## 7. 实施记录

### M1：认证与用户管理（完成于 2026-07-18，verify 全绿）

- **schema**：`V4__auth_and_users.sql`——users 表补 `password_hash`/`avatar_url`/`agent_quota`；新建 `auth_sessions`（token PK / user_id / created_at / expires_at，SQLite 单机，重启不失效）。
- **端点**：`POST /api/register`（registration-open 门控；首个设密码的用户自动 super_admin 且不受门控，种子 local-user 无密码不参与计数）、`POST /api/login`（login 字段支持用户名或邮箱，用户不存在与密码错误统一 401 口径）、`POST /api/logout`、`GET/PUT /api/me`、`POST /api/me/password`。登录/注册成功写 HttpOnly + SameSite=Lax cookie（`openagent_session`，7 天）。
- **认证设施**：`AuthFilter`（OncePerRequestFilter，/api/** 白名单 login/register/onboard/status）+ framework 层 `RequestContext`/`RequestIdentity`（ThreadLocal，请求结束清理）；BCrypt 只引 spring-security-crypto。未认证 401 响应为 fastclaw 兼容 `{"ok":false,"error":"unauthorized"}`。
- **身份传递**：`LOCAL_USER_ID` 主代码使用点全部改从 RequestContext 取；`AgentRunCoordinator` 在提交线程快照 userId 传入异步链路；`/api/status` 保持公开，匿名回退种子 local-user 视图。
- **测试**：`AuthEndpointsTest`（首用户超管/注册登录改密/登出失效/过期会话剔除/401/白名单放行）、`RegistrationClosedTest`；测试基建 `TestAuthSessionFilter`（自动注入种子 super_admin 会话 cookie，存量测试零改造）、`TestAuthSessionSeeder`、`TestIdentity.callAs`（绕过 Web 层的身份包裹）。

### M2：归属隔离 + RBAC + API Key（完成于 2026-07-19，verify 全绿 187 测试）

- **owner 校验**：统一收口为 `AgentService.requireAccess(id)`——agent 不存在或当前用户非属主（super_admin 豁免）一律 404 不暴露存在性；API key 带 agent 子集时目标不在子集内 403。铺开面（12 类端点，逐一入负向矩阵）：`AgentServiceImpl` 全部读写方法（agents CRUD + config）；`ChatServiceImpl.history/sessions/replayEventsSince` + `ChatController.stream`（chat 四端点；`AgentRunCoordinator.start` 刻意不校验——有测试以普通 user 身份直调，校验统一放在 Web/service 入口）；`AgentFileController`（files 三端点）；`MemoryController`、`SkillController`（经已有 getAgent 调用自动覆盖，含 `skills/upload?agent=`）；`ToolServiceImpl`（tools 三端点，原 exists 检查替换为 requireAccess）；`WorkspaceHistoryController`（history 两端点）。`GET /api/agents` 列表在 key 带 scope 时按子集过滤。
- **RBAC**：`/api/users` CRUD + `POST /api/users/{id}/password`（契约对齐前端 api.ts admin 系列：列表 `{users:[...]}`、创建/更新 `{ok,user}`、重置密码后会话全失效、停用立即踢会话、不允许删除自己 400、角色/状态枚举校验、用户名/邮箱重复 409）。普通 user 调这些端点与 `/api/admin/registration` 一律 403（`UserAdminServiceImpl.requireSuperAdmin` 闸门）。`/api/admin/registration` GET/PUT `{open}`：注册开关改由 configs 表 `admin.registrationOpen` 持久化、立即生效，未设置时回落 `openagent.registration-open`（M1 门控行为与 RegistrationClosedTest 不变）；`/api/status` 的 registrationOpen 同步改读有效值。登录与会话认证均校验 `status=active`（停用账号 401 同"密码错误"口径）。
- **API Key**：`V5__api_keys.sql`（id / user_id / name / key_hash / agent_ids JSON / created_at / last_used_at，key_hash 唯一索引）。`POST/GET/DELETE /api/apikeys` 对齐前端 apikeys 页契约：创建 `{apikey, token}`（`oag_` 前缀 64 hex 明文只返回一次，库存 SHA-256）；列表 `{apikeys:[{id,userId,name,key(打码),type,agents,createdAt,lastUsedAt}]}`；删除按属主隔离（他人 key 404）。`AuthFilter` 无 cookie 时回落 `Authorization: Bearer <key>`，命中即携带 key 的 agent 子集身份并惰性刷新 last_used_at。
- **onboard 建号**（M1 遗留建议，前端契约清晰故落地）：onboard 页有 username/email/password 字段——三字段齐备且库内无密码用户时创建 super_admin，首个业务 agent 归属该账号；字段不全静默跳过（兼容只写供应商配置的存量调用）；已完成引导后重复 onboard 不再建号（幂等）。
- **越权负向测试矩阵**（发布门禁）：`OwnershipIsolationTest`（B 访问 A 的 agent 12 类端点全 404 + 不存在同口径 + super_admin 豁免 200）；`ApiKeyFlowTest`（scope 内 200 / scope 外 403、列表过滤、明文一次性、打码、绑定他人 agent 404、跨用户删 key 404、删后 401、无效 key 401）；`UserAdminEndpointsTest`（普通用户管理端点全 403、超管全链路、停用/重置密码生效、注册开关读写）；`OnboardAccountTest`（建号 + agent 归属 + 幂等）。
- **偏差与说明**：
  - `api_keys` 表按计划无 type 列：创建时仅 `type=agent` 语义落地（存 agentIds 子集），admin/user 均为不限制；列表 type 由 agents 是否为空推导。前端的 `rotateApikey`、`setApikeyAgents`（/rotate、/agents 端点）本期未实现，页面调用会收到 404 错误提示，留待后续。
  - Bearer 只识别 `oag_` 前缀 API key，不识别 session token（前端 cookie 为主凭证）。
  - scope 检查对所有身份生效（super_admin 的 scoped key 同样受限）；owner 检查仅 super_admin 豁免。
  - 密码最小长度 8（与 signup 页一致）；前端 onboard 页校验为 ≥6，6–7 位密码会被后端拒绝并回显错误——前端两页校验不一致，待前端对齐。
  - `GET /api/agents?all=true`、`/api/admin/chats`、全局技能删除的 admin 门、agentQuota 硬配额均不在本期，维持原状。
  - 存量 `AgentLifecycleEndpointsTest` 因 onboard 语义变化适配夹具（密码改 8 位、首个 agent 归属新建账号断言、测试库每次重建）。

### M3：配置继承链（system → user → agent 三级）（完成于 2026-07-19，verify 全绿 198 测试）

- **schema**：`V6__configs_scope.sql`——SQLite 不支持改主键，重建 `configs` 表：PK 改为 `(scope, scope_id, config_key)`；存量行归位约定：`skills.agentEntries.{agentId}` 键 → `scope='agent'`、`scope_id=agentId`（agent 级配置的本然归属），其余键（agents.defaults / skills.entries / prefs / sandbox / admin.registrationOpen）→ `scope='system'`、`scope_id=''`（空串为 system 的统一约定）。
- **ConfigRepository**：全部方法按 `(scope, scopeId, key)` 三元组寻址；`delete(key)` 删除全 scope 行（agent 删除时清理 agentEntries 键）；`listByScopeAndPrefix` 取代原 `listByPrefix`。
- **读路径（merged = agent ⊕ user ⊕ system）**：
  - setting 类键（`agents.defaults`/`skills.entries`/`prefs`/`sandbox`，`ConfigService.SETTING_KEYS`）字段级深合并，user 覆盖 system；`skills.entries` 按技能条目再字段级合并（enabled/apiKey 覆盖、env 按键合并）。
  - 集合外任意键按 provider/model 类语义**整对象替换**（user 行存在即整体生效，不再继承 system）——本期 configs 键空间内尚无 provider 键（provider 连接配置在 `providers` 表），规则在合并层实现并以 `providers.openai` 模拟键单测锁定，待 provider 键落入 configs 时直接生效。
  - agent 级经 `skills.agentEntries.{agentId}` 键表达（agent scope）：`skillEnabled` 解析链 = agent scope 行 → **agent 属主**的 user scope 行 → system 行 → 默认启用（属主解析经 AgentRepository，异步运行线程无 RequestContext 也正确）；GET agentEntries 普通用户仅见自己 agent 的覆盖，super_admin 见全部。
  - 无身份上下文（异步线程/内部调用）读侧回落 system scope 视图。
- **写路径（按身份落 scope）**：super_admin 写 system scope；普通用户写自己的 user scope——**写隔离采用"静默落 user scope"语义**（POST /api/config 无 scope 参数、前端零改动，普通用户无从触及 system scope，而非 403）；无身份上下文的内部/测试调用回落 system scope（单机存量语义）。agent 级沿用 agentEntries 键写 agent scope，ConfigController 在写入前 `requireAccess(agentId)`（越权 404，与 M2 同口径）。`admin.registrationOpen` 恒读写 system scope。
- **打码逐 scope 生效**：GET 回显合并视图的打码；POST 回写值与**合并视图**打码一致时不在本 scope 落值（保留本 scope 现状，无值即继续继承上级），明文新值才写入本 scope；agent scope 写入的打码比较基准为本 scope 行（GET agentEntries 回显即 agent scope 行的打码，语义与 V7 一致）。
- **GET /api/config 响应形状不变**（前端零改动），值为当前身份的合并视图。
- **测试**：`ConfigScopeTest`（字段级三级合并、provider 整对象替换、skillEnabled 三级链、写隔离——普通用户写不进 system scope、super_admin 落 system、跨 scope 打码回写保护、逐身份打码、agentEntries 属主可见性）；`ConfigScopeEndpointsTest`（多用户全链路：admin 写 system → 用户读合并视图、用户写只落 user scope 且不影响他人、打码回写跨 scope 保护、agentEntries 越权 404 + 属主可见性）；回归 V7 `ConfigServiceTest`/`ConfigEndpointsTest` 全绿。测试基建新增共享夹具 `InMemoryConfigRepository`/`InMemoryAgentRepository`（取代 ConfigServiceTest/SkillServiceTest/ExecToolTest/DockerSandboxServiceTest 各自的旧版内存 Stub）。
- **偏差与说明**：
  - `ConfigEndpointsTest` 的 agentEntries 用例 agentId 由 `agent-1` 改为种子 `default`——写入路径新增归属校验后，不存在的 agent 按 M2 口径 404。
  - `sandboxEnabledOverride()`/`agentDefaults()`/`prefs()` 读合并视图（有身份时 user 覆盖 system）；运行时调用方（DockerSandboxService 等）在异步线程无身份，实际生效为 system scope——sandbox 属平台级关注，语义可接受，特此记录。
  - provider 连接配置仍在 `providers` 表（onboard/种子写入），前端 models 页期待的 `/api/providers` scoped CRUD 端点本期未实现（页面调用 404，与 M2 记录一致）；GET /api/config 的 providers 段仍由 ModelSettings 派生。
