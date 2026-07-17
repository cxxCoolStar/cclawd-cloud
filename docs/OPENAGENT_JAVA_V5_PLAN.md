# OpenAgent Java V5 Skill 模块实施方案

> 版本：V5.0 方案稿  
> 编制日期：2026-07-17  
> 前置版本：V4 完成态（Docker 沙箱 + exec，见 [OPENAGENT_JAVA_V4_PLAN.md](OPENAGENT_JAVA_V4_PLAN.md)）  
> 版本切分说明：经 2026-07-17 讨论，用户决定先做 Skill 模块（MCP 顺延 V6）；V5 只跑最小闭环——本地技能发现、prompt 注入、load_skill、管理 API 与 ZIP 上传

## 1. 版本定位

让 Agent 具备"按需加载技能"的能力：技能作者把一个含 `SKILL.md` 的目录放进技能目录，模型就能在 system prompt 的技能目录里看到它，并通过 `load_skill` 工具读取完整操作指引执行。判断标准：

```text
ZIP 上传/目录放入技能
  -> GET /api/skills 可见
  -> 下一轮对话的 system prompt 出现技能摘要
  -> 模型自主调用 load_skill 获取全文
  -> 按指引（可结合 exec/文件工具）完成任务
```

## 2. FastClaw 行为基线（explore 调研，2026-07-17）

| 能力 | FastClaw 参考 | 必须保留的行为 |
|---|---|---|
| SKILL.md 格式 | internal/agent/skills.go:40-95 | YAML frontmatter（name/description/metadata.fastclaw.env 等）+ Markdown 正文；**目录名是技能 key** |
| 分层发现 | skills.go:164-294 | 逐层扫描、同名高优先级遮蔽、结果按名排序 |
| prompt 注入 | skills.go:302-407 | `<skill_catalog>` 一行一技能（name — 一句描述），全文不内联；skills_usage_rules 指令 |
| load_skill | internal/agent/tools/load_skill.go | 参数 {name}，返回 SKILL.md 全文（{baseDir} 已替换） |
| 管理 API | internal/setup/handlers_skills.go、skill_install.go | 列表形状 {name,description,location,type,envSpec}；ZIP 上传校验 SKILL.md、防 zip-slip |
| env 规格 | skills.go:90-95 | envSpec [{name,description,required,secret}] 随列表返回 |

V5 收缩（明确不交付）：

- 远程安装（skills.sh/clawhub/github 搜索与下载）；
- gating（requires.bins/env、os 检查与 unavailable 标注）；
- `always`/`alwaysLoad` 全文内联；启停与 env 配置（依赖 /api/config 配置体系，V5 不做）；
- bundled 技能、对象存储同步、技能目录挂载进沙箱（/skills mount）、exec 的技能 env 注入、skills_learner 自动学习；
- 前端技能页面的启停/编辑联调（页面可读列表；启停按钮待配置体系版本）。

## 3. 设计

### 3.1 目录布局

```text
{skillsDir}/                    # 全局技能（默认 ./skills，openagent.skills.dir 可配）
  web-search/SKILL.md
{workspaceRoot}/{agentId}/skills/   # Agent 私有技能（最高优先级，同名遮蔽全局）
  my-skill/SKILL.md
```

### 3.2 模块落位

| 能力 | 落位 |
|---|---|
| `SkillService`（扫描、解析、摘要、安装/删除） | `bootstrap/skill/` |
| `SkillProperties`（skills.dir） | `bootstrap/skill/config/` |
| `LoadSkillTool` | `bootstrap/tool/adapter/` + `ToolCatalog`（默认启用、只读 MEDIUM） |
| `SkillController` | `bootstrap/skill/controller/` |
| prompt 注入 | `PersistedConversationFactory.buildSystemPrompt` 追加技能段落 |

### 3.3 SKILL.md 解析

SnakeYAML 解析 frontmatter（`---` 包裹段）：`name`、`description`、`homepage`、`metadata.fastclaw.env[]` 与顶层 `env[]`（顶层优先）。解析失败容忍：无 frontmatter 时 description 回退正文首个非 `#` 行（fastclaw scanSkillsDir 同款）。正文 `{baseDir}` 在 load_skill 返回时替换为技能目录绝对路径。

### 3.4 system prompt 注入（fastclaw BuildSkillsSummary 对齐）

```text
<skill_usage_rules>（固定指令：先用 load_skill 读取技能全文再按其操作）</skill_usage_rules>

<skill_catalog>
Pre-installed skills available to this agent. Call `load_skill` with the skill name to load the full instructions.
- web-search — Search the web and fetch web pages
</skill_catalog>
```

无技能时不注入任何段落（避免污染 prompt）。

### 3.5 API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/skills` | 全局技能列表（SkillInfo[]） |
| GET | `/api/agents/{id}/skills` | Agent 私有技能列表 |
| DELETE | `/api/skills/{name}` | 删除全局技能 |
| DELETE | `/api/agents/{id}/skills/{name}` | 删除 Agent 私有技能 |
| POST | `/api/skills/upload` | ZIP 上传安装（multipart 字段 `file`，可选 `name`、`?agent=<id>`） |

ZIP 上传规则（fastclaw 对齐）：≤64MiB；单顶层目录剥离或根级文件自动包裹；根下必须有 `SKILL.md`；防 zip-slip、跳过符号链接；响应 `{"ok":true,"source":"upload","name":...,"installedAt":...,"files":[...]}`。

### 3.6 安全

- 技能名/目录名校验：只允许 `[a-zA-Z0-9._-]`，防路径穿越；
- ZIP 条目逐个校验目标路径位于安装目录内；
- DELETE 只作用于技能目录，不允许 `..`；
- load_skill 只读 SKILL.md，不执行任何内容。

## 4. 里程碑

### M1：发现、注入与 load_skill（1 天）

- SkillService 扫描两层目录、SKILL.md 解析、摘要构造；
- system prompt 注入；LoadSkillTool 注册；
- 单测：解析（完整/无 frontmatter/坏 YAML 容忍）、遮蔽、摘要格式、load_skill 全文与 {baseDir} 替换。

### M2：管理 API 与 ZIP 上传（1 天）

- 列表/删除/上传接口；zip-slip 防护；
- 集成测试 + 前端技能页面联调（列表可见）。

## 6. 实施记录（2026-07-17 完成）

### M1+M2：Skill 模块 ✅ 已完成（约 1 天）

- **交付物**：
  - `bootstrap/skill/SkillService.java`（两层扫描 + Agent 遮蔽、SKILL.md frontmatter 解析（顶层 env 优先于 metadata.fastclaw.env）、`<skill_catalog>` 摘要、ZIP 安装（顶层目录剥离/zip-slip 防护/SKILL.md 强制校验/同名覆盖）、删除）
  - `bootstrap/skill/config/SkillProperties.java`（`openagent.skills.dir`，默认 `./skills`）
  - `bootstrap/skill/controller/SkillController.java`（列表×2、删除×2、ZIP 上传）
  - `bootstrap/tool/adapter/LoadSkillTool.java` + `ToolCatalog` 注册（默认启用、MEDIUM）
  - `PersistedConversationFactory.buildSystemPrompt` 追加技能摘要段落（无技能不注入）
- **参考文件**：fastclaw `internal/agent/skills.go`、`internal/agent/tools/load_skill.go`、`internal/setup/handlers_skills.go`、`skill_install.go`（explore 调研报告）
- **测试**：`SkillServiceTest`（13 用例：解析/回退/坏 YAML 容忍/遮蔽/摘要格式/{baseDir} 替换/名校验/ZIP 剥离与强制校验/Agent 目录安装/删除/截断）；`SkillEndpointsTest`（5 用例：列表×2/上传/无 SKILL.md 拒绝/删除）；`ToolSeedTest` 默认启用集更新；全量 `mvnw verify` 102 tests 全绿
- **真实端到端验证（2026-07-17，kimi-k2.5）**：ZIP 上传 fib-skill → `GET /api/skills` 可见 → 对话"计算斐波那契前 15 项"，模型自主 `load_skill` 读取全文 → 按技能指引 `write_file` 写脚本 → `exec` 容器内运行 → 整理输出，全链路事件顺序正确
- **有意收缩（与方案 2 一致）**：无 gating/alwaysLoad/启停 env 配置/远程安装/沙箱 /skills 挂载与技能 env 注入；技能启停按钮依赖后续 /api/config 配置体系版本

## 5. 验收

1. ZIP 上传一个技能后 `GET /api/skills` 可见，形状与前端 SkillInfo 一致；✅（实测通过）
2. 下一轮对话模型能看到技能摘要并自主调用 `load_skill` 拿到全文；✅（实测通过）
3. Agent 私有技能同名遮蔽全局；✅（单测覆盖）
4. zip-slip 与非法技能名被拒绝；✅（单测覆盖）
5. 全量 `mvnw verify` 全绿；真实 kimi-k2.5 smoke 通过。✅

## 7. 后续版本预留

- V6：MCP 客户端（stdio + HTTP/Streamable HTTP、动态工具注册、断线重连）；
- V5.1 候选：技能启停与 env 配置（依赖 /api/config 配置体系）、技能目录挂载进沙箱（/skills，排除 SKILL.md）、exec 的技能 env 注入、gating 与 alwaysLoad、远程安装（skills.sh/clawhub）、`/api/config` 体系。
