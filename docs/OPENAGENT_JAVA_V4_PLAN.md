# OpenAgent Java V4 Docker Sandbox 与 exec 实施方案

> 版本：V4.0 方案稿  
> 编制日期：2026-07-17  
> 前置版本：V3 完成态（记忆与上下文，见 [OPENAGENT_JAVA_V3_PLAN.md](OPENAGENT_JAVA_V3_PLAN.md)）  
> 关联文档：[OPENAGENT_JAVA_REFACTORING_PLAN.md](OPENAGENT_JAVA_REFACTORING_PLAN.md)（6.6 节）、[OPENAGENT_JAVA_V1_PLAN.md](OPENAGENT_JAVA_V1_PLAN.md)（3.1 命令执行决策）  
> 版本切分说明：经 2026-07-17 讨论，用户决定跳过 Skill/MCP 先做 Sandbox + exec，优先打通"写代码 → 执行 → 看结果 → 继续推理"的工具闭环；Skill/MCP 顺延至 V5

## 1. 版本定位

V4 让 Agent 从"能读写文件"升级为"能运行代码"：模型可以用文件工具写脚本，再用 `exec` 在 Docker 容器里执行，拿到真实输出继续推理。核心闭环：

```text
用户任务
  -> 模型 write_file 写脚本到会话 workspace
  -> 模型 exec 在 Docker 容器中执行（workspace 已挂载）
  -> 真实 stdout/stderr/退出码作为 tool result 回传
  -> 模型根据结果修正代码或给出结论
```

判断标准：命令执行**只发生在 Docker 容器内**，宿主机不存在任何 shell 执行入口；未启用 Docker 时 `exec` 不可调用；容器有明确的资源限额与生命周期管理。

## 2. FastClaw 行为基线（已对照源码核实，2026-07-17）

| 能力 | FastClaw 参考文件 | 必须保留的行为 |
|---|---|---|
| 沙箱接口 | internal/sandbox/executor.go | Executor 抽象，文件/命令统一走沙箱 |
| Docker 实现 | internal/sandbox/docker.go（487 行） | docker CLI（不引入 SDK）、长驻容器、懒创建、绑定挂载 |
| 执行 | docker.go Exec/ExecWithStdin | `docker exec sh -c`、stdout+stderr 合并、错误文本附带 Error 行 |
| 生命周期 | internal/sandbox/lifecycle.go | 容器复用与关闭清理 |
| 工具契约 | internal/agent/tools/registry.go:286 | sandboxRequired：exec 只能经沙箱注册，**绝不回落宿主机执行** |

从 docker.go 提取的关键行为：

1. `docker create --interactive --label fastclaw=sandbox ... <image> tail -f /dev/null` + `docker start`，懒创建（首次 Exec 时才建）；
2. workspace 以绑定挂载进容器 `-v <host>:/workspace:rw`，hydrate/sync 由文件系统天然解决；
3. 资源限额来自 Policy：`--cpus`、`--memory`、`--network`；
4. 默认 NetMode 留空（bridge，允许出网）——注释明确：沙箱需要出网做 pip install，设 `none` 曾破坏 generate-image 类技能；
5. `docker exec [-w workdir] <id> sh -c <command>`，CombinedOutput，非零退出时输出附带 `\nError: ...`；
6. `Close()` 为 `docker rm -f`，尽力而为；
7. 端口发布只绑 127.0.0.1（V4 不做 preview，仅记录该原则）。

## 3. V4 范围

### 3.1 必须交付

- `SandboxBackend` 端口：按 agent 懒创建/复用长驻容器、执行命令、关闭清理；
- Docker 实现：docker CLI（ProcessBuilder，不引入 Docker SDK 依赖）；
- agent 级容器：`{workspaceRoot}/{agentId}` 挂载到容器 `/workspace`，exec 默认工作目录为 `/workspace/sessions/{sessionId}`（与文件工具的会话 workspace 一致）；
- `exec` 工具：HIGH 风险、默认禁用（agent_tools 需显式启用）+ 全局 `openagent.sandbox.docker-enabled` 双重门控（与 web_fetch 同模式）；
- 资源限额：可配置 cpus（默认 1）、memory（默认 512m）、network（默认 bridge 对齐 fastclaw，可配 none）；
- 输出合并 stdout/stderr、超时取消（尊重 ToolExecutionContext.deadline）、错误结果结构化返回模型；
- 生命周期：进程关闭时移除本进程创建的容器；启动时尽力清理带 `openagent=sandbox` 标签的遗留容器；
- 镜像可配置，默认 `python:3.12-slim`（代码执行实用性；fastclaw 用自定义镜像，V4 不构建自定义镜像）。

### 3.2 明确不交付

- E2B、BoxLite 后端；
- workspace hydrate/sync 的对象存储实现（绑定挂载已覆盖单机）；
- 端口发布与项目预览（preview runtime）；
- skill 目录挂载（属 V5 Skill 主题）；
- 多副本容器调度、容器池化与空闲回收（V4 容器随进程生命周期）；
- Windows 容器 / 非 Linux 镜像支持。

## 4. 设计

### 4.1 模块落位

| 能力 | 落位 | 说明 |
|---|---|---|
| `DockerSandboxService` | `bootstrap/sandbox/` | 按 agentId 管理容器（ConcurrentHashMap）、懒创建、exec、清理；docker CLI 封装为可替换的 `DockerCli` 接口便于无 Docker 环境单测 |
| `SandboxProperties` | `bootstrap/sandbox/config/` | `openagent.sandbox.*` |
| `ExecTool` | `bootstrap/tool/adapter/` | 实现 agent-core AgentTool 端口，沿用 V2 20.4 工具承载决策 |
| 目录注册 | `ToolCatalog` | `exec`，HIGH，默认禁用 |

### 4.2 容器模型

```text
容器名：openagent-sandbox-{agentId}
镜像：  python:3.12-slim（可配）
挂载：  {workspaceRoot}/{agentId} -> /workspace:rw
工作目录：/workspace/sessions/{sessionId}（exec 时 -w 指定）
常驻命令：tail -f /dev/null
标签：  openagent=sandbox
限额：  --cpus 1 --memory 512m --network bridge
```

- 同一 agent 的所有会话共享一个容器（与 fastclaw 的 agent 级沙箱一致）；
- 首次 exec 时懒创建；容器已存在且运行中则复用（`docker inspect` 判断，被外部删除时重建）；
- 进程退出 `@PreDestroy` 移除创建过的容器；启动时 `docker ps -aq --filter label=openagent=sandbox` 尽力清理上次遗留。

### 4.3 exec 工具协议

```json
{
  "command": "python script.py",
  "workdir": ".",
  "timeout": 30
}
```

- `command` 必填，经 `sh -c` 执行；
- `workdir` 可选，相对会话 workspace 解析，禁止 `..` 逃逸出会话目录；
- `timeout` 可选，受 `openagent.tools.execution-timeout` 与 run deadline 双重约束（取早者）；
- 结果：stdout+stderr 合并文本；非零退出码追加 `\nExit code: N`（fastclaw 附 Error 行的等价表达）；超时/异常映射为 `TOOL_TIMEOUT` / `TOOL_EXECUTION_FAILED`；
- 输出长度由现有 `PersistingToolInvoker` 统一截断。

### 4.4 配置项

```yaml
openagent:
  sandbox:
    docker-enabled: ${OPENAGENT_SANDBOX_DOCKER_ENABLED:false}
    image: ${OPENAGENT_SANDBOX_IMAGE:python:3.12-slim}
    cpus: ${OPENAGENT_SANDBOX_CPUS:1}
    memory: ${OPENAGENT_SANDBOX_MEMORY:512m}
    network: ${OPENAGENT_SANDBOX_NETWORK:bridge}
```

`docker-enabled` 键已存在于 application.yml（V2 预留），本次补齐属性类与消费方。

### 4.5 安全红线

1. `exec` 只经 DockerSandboxService 执行，代码库中不得出现任何宿主机 shell 执行路径（ArchUnit/评审检查）；
2. 双重门控：全局 `docker-enabled=false` 时即使 agent_tools 启用也返回 `TOOL_NOT_ENABLED`；
3. 容器限额强制带上（cpus/memory），network 可配但不为空值以外的非法值；
4. workdir 解析后必须位于会话 workspace 内；
5. 日志不记录完整命令输出，只记命令摘要、耗时与退出码。

## 5. 测试与验收

### 5.1 自动化测试（无 Docker 守护进程也可跑）

- docker CLI 参数构造单测：create/exec 参数序列、限额与挂载、workdir 逃逸拒绝；
- `DockerCli` fake 的容器生命周期测试：懒创建、复用、外部删除后重建、关闭清理；
- ExecTool 门控测试：docker 未启用 / agent_tools 未启用 / 参数缺失 / 超时映射；
- 全量 `mvnw verify` 不回归。

### 5.2 真实验收（需 Docker Desktop 运行）

1. 启用：`OPENAGENT_SANDBOX_DOCKER_ENABLED=true` + agent_tools 启用 exec；
2. 对话："写一个 Python 脚本计算斐波那契前 20 项并运行"，观察 write_file → exec → 结果闭环；
3. 验证宿主机无 exec 入口：`docker-enabled=false` 时调用 exec 返回 TOOL_NOT_ENABLED；
4. `docker ps` 可见 `openagent-sandbox-default` 容器及限额；进程退出后容器被清理；
5. 真实 kimi-k2.5 smoke。

## 6. 里程碑

### M1：DockerSandboxService 与生命周期（1.5 天）

- SandboxProperties、DockerCli 封装、懒创建/复用/清理、限额与挂载；
- 参数构造与生命周期单测（fake CLI）。

### M2：exec 工具与闭环（1 天）

- ExecTool + ToolCatalog 注册（默认禁用）+ 双重门控；
- workdir 安全解析、超时与错误映射；
- 集成测试（fake CLI 驱动 tool 闭环）+ 真实 Docker 冒烟（环境可用时）。

## 7. 实施记录（2026-07-17 完成）

### M1+M2：DockerSandboxService + exec 工具 ✅ 已完成（约 1 天）

- **交付物**：
  - `bootstrap/sandbox/config/SandboxProperties.java`（`docker-enabled`/`image`/`cpus`/`memory`/`network`）
  - `bootstrap/sandbox/DockerCli.java` + `ProcessDockerCli.java`（ProcessBuilder 封装，stdout/stderr 合并，超时 destroy→destroyForcibly）
  - `bootstrap/sandbox/DockerSandboxService.java`（agent 级长驻容器：懒创建/复用/停止重启/启动失败重建/外部删除重试；启动清理遗留标签容器；@PreDestroy 尽力清理）
  - `bootstrap/tool/adapter/ExecTool.java`（双重门控、workdir 逃逸拒绝、deadline 收敛、Exit code 语义）+ `ToolCatalog` 注册（HIGH、默认禁用）
  - `application.yml` 补齐 `openagent.sandbox.*` 配置键
- **参考文件**：fastclaw `internal/sandbox/docker.go`、`executor.go`、`internal/agent/tools/registry.go`（sandboxRequired 契约）
- **测试**：`DockerSandboxServiceTest`（9 用例：create 参数/复用/重启/重建/外部删除重试/异常映射/容器名净化/遗留清理/关闭跳过）；`ExecToolTest`（6 用例：门控/缺参/Exit code/超时/workdir 归一化与逃逸拒绝）；全量 `mvnw verify` 全绿（bootstrap 75 tests）
- **真实 Docker 端到端验证（2026-07-17，Docker 29.6.1 + kimi-k2.5）**：
  - "write_file 写 fib.py → exec 运行"闭环成功，容器内 Python 真实输出斐波那契前 20 项；
  - 容器检查：`openagent-sandbox-default`，1 CPU / 512MB / bridge，挂载 `workspace/default → /workspace`；
  - 非零退出码语义：`not-a-real-command` → `sh: 1: not found\nExit code: 127` 作为 observation 回传，模型正确解释；
  - 门控：`docker-enabled=false` 时 exec 返回 `TOOL_NOT_ENABLED`，模型降级使用 list_dir，运行不中断
- **有意偏离/收缩**：
  - 不做 skill 挂载、代理继承、端口发布（V4 范围外，V5 评估）；
  - 默认镜像 `python:3.12-slim` 而非 fastclaw 自定义镜像（V4 不构建自定义镜像）；
  - 非零退出码追加 `Exit code: N`（fastclaw 附 `Error: ...` 行，语义等价）；
  - 进程被强杀时容器可能残留，下次启动（docker-enabled=true）时按标签清理
- **遗留事项**：exec 启用当前需直接写 `agent_tools` 表（V2 已决策不实现 `/api/tools`，工具配置 API 留待后续版本）

## 8. 后续版本预留

- V5：Skill 加载 + MCP 客户端；skill 目录挂载进沙箱、MCP 工具经沙箱策略执行；
- 之后：容器池化与空闲回收、preview 端口发布（只绑 127.0.0.1）、E2B/BoxLite。
