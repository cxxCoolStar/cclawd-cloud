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
