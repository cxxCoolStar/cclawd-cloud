# OpenAgent Java V7 计划：配置体系（/api/config + 工具/技能启停）

- 版本：V7（方案稿，编制于 2026-07-18）
- 前置版本：V6（MCP 模块）
- 关联文档：OPENAGENT_JAVA_REFACTORING_PLAN.md、V1–V6 计划

## 1. 目标与背景

打通 FastClaw 前端已约定、后端尚未实现的配置契约，消除三个记录在案的遗留痛点：

1. **V4 遗留**：启用 `exec` 等默认禁用工具需要直写 `agent_tools` 表，没有任何 API；
2. **V5 遗留**：技能启停/env 配置没有后端（前端 `ConfigureSkillDialog` 调 `POST /api/config` 会 404）；
3. **V2 遗留（Phase 5.2）**：`GET /api/tools` 契约缺口，工具设置页 404；`GET /api/agents/{id}/tools/registered` 前端已调用但未实现。

判断标准：前端技能页的启停/env 对话框可正常保存生效；exec 可通过 API 启用而无需写 SQL；工具设置页不再 404。

## 2. 关键摸底结论（开工前已核实）

- `configs` 表（V1 migration 已建：`config_key` PK / `config_value` TEXT / `updated_at`）全仓库零引用，V7 直接复用，**无需新 migration**。
- `agent_tools` 表 + `AgentToolRepository.upsert()` 已就绪，只缺 HTTP 层。
- 模型解析已按 agent 级：`PersistedConversationFactory` 用 `agent.model()` + `providerId` → `ProviderRecord`，所以 `PUT /api/agents/{id}` 接受 `model` 后立即生效，无需额外 wiring。
- `DockerSandboxService.dockerEnabled()` 只读 `SandboxProperties`；让 `/api/config` 的 `sandbox.enabled` 生效 = 在此处加一道 DB 覆盖查询。
- `SkillService.loadAll()/buildSkillsSummary()/loadSkillContent()` 是技能注入的唯一链路，启停过滤加在这里即可全局生效。
- 前端契约（`frontend/src/lib/api.ts`）：
  - `GET/POST /api/config`：POST 是 **PATCH 深合并**语义；GET 需对 apiKey/secret env **打码**；`meta.systemDefaultModel` 用于渲染继承徽章。
  - 技能启停走 `skills.entries`（全局）/ `skills.agentEntries[agentId]`（agent 覆盖），形状 `{enabled?, apiKey?, env?}`。
  - `GET /api/agents/{id}/tools/registered` → `{tools:[{name,description,source:"builtin"|"mcp"|"plugin"}]}`。
  - 工具设置页对 `{categories:[],toolProviders:{},tools:{}}` 优雅降级（显示无类别），比 404 友好。
- FastClaw 基线：**没有 per-agent 工具启停**（agent_tools 启停是 OpenAgent 自有机制，V7 为其补 API 属超出基线的补全）；技能启停基线 = `skills.entries[name].enabled`，加载时过滤。
- FastClaw 的掩码回写保护（`mergeSkillEntry`）是死代码，V7 **真正实现**：POST 收到打码值时保留原密钥。

## 3. 设计

### 3.1 配置持久层

复用 V1 `configs` 表（单机单用户，无需 scope 列），键命名：

| 键 | 内容 |
|---|---|
| `agents.defaults` | `{model?, maxTokens?, temperature?, maxToolIterations?}` |
| `skills.entries` | `{skillName: {enabled?, apiKey?, env?}}`（全局） |
| `skills.agentEntries.{agentId}` | 同上（per-agent 覆盖） |
| `prefs` | `{timezone?}` |
| `sandbox` | `{enabled?}`（仅 enabled 可写） |

- `ConfigRepository`（JdbcTemplate）：`get(key)` / `upsert(key, json)` / `delete(key)`。
- `ConfigService`：读（DB 值 + 属性默认值合并）、写（按命名空间 PATCH）、`maskSecret`（≤8 位 → `****`，否则前4+`****`+后4，对齐 fastclaw `maskAPIKey`）与打码回写保护（POST 值仍为打码形态时保留已存原值）。

### 3.2 /api/config

- `GET /api/config` → 前端 `ConfigResponse` 形状：`providers`（由 ModelSettings 构造单个内置 provider，apiKey 打码）、`agents.defaults`（DB 值，缺省回显 ModelSettings 派生值）、`channels:{}`、`storage:{type:"sqlite"}`、`sandbox`（enabled 取 DB 覆盖 ?? SandboxProperties，image/network 等回显当前属性值）、`prefs`、`skills.entries/agentEntries`（secret 打码）、`meta{systemDefaultModel, serverTimezone}`。
- `POST /api/config`：PATCH 语义，仅处理出现的子树：`agents.defaults`、`skills.entries`、`skills.agentEntries`、`prefs`、`sandbox`（仅 `enabled` 可写并生效，其余字段忽略——仍由环境变量配置）。响应 `{ok:true}`。
- 生效 wiring：`DockerSandboxService.dockerEnabled()` 改为 `configService.sandboxEnabledOverride().orElse(sandboxProperties.dockerEnabled())`。

### 3.3 技能启停生效

- `SkillService` 注入 `ConfigService`：`loadAll()`/`buildSkillsSummary()` 过滤 `enabled=false`（agentEntries[agentId] 覆盖优先于全局 entries，对齐 fastclaw）；`loadSkillContent()` 对禁用技能返回 empty（`LoadSkillTool` 自然报"未找到"）。
- 技能 env/apiKey：本版本只存储与回显（打码），不注入 exec/沙箱（V5.1 遗留）。
- 列表 API 形状不变（前端自行从 /api/config 合并 enabled 状态）。

### 3.4 工具启停 API + 契约补齐

- `ToolController`：
  - `GET /api/agents/{id}/tools` → `{tools:[{name,description,riskLevel,enabled,source}]}`（管理视图）。
  - `PUT /api/agents/{id}/tools/{toolName}` body `{enabled:bool}` → `AgentToolRepository.upsert`；`exec` 仍需全局 `dockerEnabled` 双重门控（语义不变）；MCP 工具名（`mcp_` 前缀）返回 400 并提示走 server 配置（fastclaw 同款"server 配置即启用"）。
  - `GET /api/agents/{id}/tools/registered` → 前端契约形状 `{tools:[{name,description,source}]}`（live registry）。
- `GET /api/tools`：返回 `{categories:[],toolProviders:{},tools:{}}`（消除 404、页面优雅降级；完整 provider chain 留 V8）。`PUT /api/tools` 返回 `FEATURE_NOT_AVAILABLE` 业务错误（对齐总方案"延期接口"约定）。
- `PUT /api/agents/{id}`：在 mcpServers 之外新增接受 `name`、`description`、`model`（`""` = 清除覆盖回退种子默认值，经 `AgentRepository.updateSettings`）。其余前端字段（promptMode/splitReplies/autoPersist/shareModelConfig/plugins 等）保持忽略——这些在 OpenAgent 尚无后端语义，存而不生效比忽略更有害。
- 修复：`PlatformCapabilities.mcp` 恒 false → true（V6 已交付 MCP）。

## 4. 里程碑

- **M1（约 1 天）**：`ConfigRepository` → `ConfigService`（+掩码）→ `ConfigController`（GET/POST /api/config）→ sandbox wiring → 测试（PATCH 合并、掩码、打码回写保护、缺省回退、GET 形状逐字段对前端 ConfigResponse、secret 不泄漏）。
- **M2（约 0.5 天）**：SkillService 启停过滤 → 测试（全局禁用、agent 覆盖解禁/禁用、load_skill 禁用拒绝）。
- **M3（约 0.5 天）**：ToolController 三端点 → PUT /api/agents 字段扩展 → capabilities 修复 → 测试。

## 5. 发布门禁

- `mvnw verify` 全绿；
- 前端契约逐字段核对（ConfigResponse / registered tools / SkillInfo）；
- 真实 kimi-k2.5 smoke：禁用技能后 prompt 无该技能 → 启用后恢复；API 启用 exec 后工具循环可用（Docker 环境下）。

## 6. 明确不交付

- `/api/tools` 完整 provider chain（web_search/searxng 配置化）→ V8
- 技能 env 注入 exec/沙箱、gating、alwaysLoad、远程安装 → V5.1/V8
- `agents.defaults.model` 的运行时继承（存储+回显；运行时按 agent 级 model 已是现状）
- MCP 全局作用域、连接测试、stdio 沙箱化 → V8+
- 多用户作用域（system/user/agent 链）——单机单用户模式，键空间已预留 agent 维度

## 7. 实施记录（2026-07-18 完成）

### M1：配置持久层 + /api/config ✅

- 新增 `persistence/ConfigRepository`（get/upsert/delete/listByPrefix）、`config/ConfigService`（PATCH 合并、`maskSecret`、looksLikeSecret、打码回写保护、`sandboxEnabledOverride`、IANA 时区校验、`skillEnabled`）、`config/controller/ConfigController` + `vo/ConfigResponseVO`。
- `DockerSandboxService.dockerEnabled()` 接入 DB 覆盖。
- 偏差记录：GET 额外回显 `sandbox.backend/dockerImage` 与 `hooks:{enabled:false}` 占位（前端 ConfigResponse 必填字段，不返回破坏契约）。
- 测试：`ConfigServiceTest`（10 用例）、`ConfigEndpointsTest`（4 用例，含响应全文无明文密钥断言、打码回写后原密钥保留、非法时区 400 不落库）。

### M2：技能启停生效 ✅

- `ConfigService.skillEnabled(agentId, name)`：per-agent 条目优先于全局，均无默认启用；`enabled` 为 null 视为未指定继续回退。
- `SkillService.loadAll/buildSkillsSummary` 过滤禁用技能；`loadSkillContent` 对禁用技能返回 empty；列表视图不过滤（前端自行合并）。
- 测试：`SkillServiceTest` 新增 6 用例（全局禁用、agent 覆盖解禁/禁用、默认启用、loadSkillContent 禁用、列表不过滤）。

### M3：工具启停 API + 契约补齐 ✅

- 新增 `tool/controller/ToolController` + `tool/service/ToolService(Impl)`：`GET /api/agents/{id}/tools`（管理视图）、`PUT /api/agents/{id}/tools/{toolName}`、`GET /api/agents/{id}/tools/registered`（前端契约）、`GET /api/tools`（空目录）、`PUT /api/tools`（400 feature not available）。
- `CatalogToolRegistry` 新增 `assembledBuiltinTools()` 供管理视图复用交集逻辑。
- `PUT /api/agents/{id}` 新增 name/description/model（`""` 回退 `ModelSettings.name()`）；`AgentRepository` 新增 `updateProfile`/`updateModel`。
- `PlatformCapabilities.mcp` 修复为 true。
- 偏差记录：目录外工具名返回 404（资源不存在语义）而非 400；`PUT /api/tools` 用 400 + message 表达 feature not available（框架错误体无 code 字段）。
- 测试：`ToolEndpointsTest`（7 用例）、`AgentConfigEndpointsTest` 新增 2 用例、`PlatformEndpointsTest` 适配。

### 计划外修复：DataSeeder 不再覆盖默认 Agent 的 model/systemPrompt ✅

M3 实施中发现 `seedDefaultAgent` 每次启动用 `ModelSettings` 刷新 agent 的 model/system_prompt，会使用户通过 PUT 设置的 model 在重启后被重置。改为只播种不刷新（环境变量仅作首次启动默认值；供应商连接配置仍每次刷新）；删除因此失去调用方的 `AgentRepository.updateSettings`；README 配置表补充说明。

### 门禁

`./mvnw verify` 全绿（bootstrap 156 tests，0 失败；framework/agent-core/infra-ai 全绿）；前端契约逐字段核对通过（ConfigResponse / registered tools / SkillEntryCfg，对 `frontend/src/lib/api.ts`）。真实模型 smoke（禁用技能 → prompt 无该技能 → 启用恢复；API 启用 exec 后工具循环）未在本版本窗口执行，留作下次启动服务时的例行验证。

构建期间两次失败为环境问题（机器页面文件不足导致 JVM 无法 fork，DOS error 1455；ByteBuddy 自附加随之失败），释放内存后复跑全绿，与代码无关。
