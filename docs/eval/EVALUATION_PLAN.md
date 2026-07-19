# OpenAgent Java 评测（Eval）实施方案

> 版本：v1.2（Phase 1 已完成落地）  
> 日期：2026-07-19  
> 关联文档：[`ai-agent-evaluation-system-spec.md`](./ai-agent-evaluation-system-spec.md)（FastClaw 参考方案）

## 0. 进度

| 阶段 | 状态 | 备注 |
|------|------|------|
| Phase 0 手动 Eval | 🔲 未开始 | 手动用例可随时积累（直接写 YAML 草稿进 `eval/cases/`） |
| Phase 1 Trace 导出 + Token 落库 | ✅ 完成（2026-07-19） | V7 迁移 + `TokenUsageHook` + `TraceService` + trace 端点 |
| Phase 2 最小自动评分 | 🔲 未开始 | |
| Phase 3 多 Trial + 回归门禁 | 🔲 未开始 | |
| Phase 3.5 Record/Replay | 🔲 未开始 | |

## 1. 背景与现状

### 1.1 已有基础设施

经过前期调研，当前项目已具备开展 eval 的良好基础：

| 组件 | 现状 | 对 Eval 的价值 |
|------|------|----------------|
| `ReActAgentKernel` | 显式状态机循环 | 评测对象稳定，行为可预期 |
| `AgentEventSink` | 事件流接口 | 可接入自定义 trace 收集器 |
| `session_events` 表 | 完整事件持久化 | 可回放、可审计 |
| `agent_runs` / `tool_executions` 表 | 运行与工具轨迹 | 效率、路径评分的数据源 |
| Hook 机制（新增） | 7 个挂载点 | `AFTER_MODEL_CALL`/`AFTER_TOOL_CALL`/`POST_TURN` 可作为 eval trace 入口 |
| 测试基建 | JUnit 5 + Spring Boot Test | 可复用现有测试体系 |

### 1.2 与 FastClaw Spec 的关系

参考文档 [`ai-agent-evaluation-system-spec.md`](./ai-agent-evaluation-system-spec.md) 是一份**方法论完备但工程过重**的方案，适合作为 6-12 个月后的目标形态，但**不适合作为起步方案**。

**本方案取其精华，去其过重部分**：

| FastClaw Spec | 本方案取舍 | 原因 |
|---------------|-----------|------|
| 9 张独立 eval 表 + 完整数据模型 | ❌ 暂不实施 | 首版无需独立数据模型，复用现有表 +
临时文件存储 |
| 11 个 HTTP API + 产品化界面 | ❌ 暂不实施 | 首版用 CLI + 本地报告，后续按需产品化 |
| YAML/JSON 用例定义 | ✅ 保留 | 必要性高，人工编写友好 |
| 三层评分（确定性→Rubric→人工） | ✅ 保留 | 方法论正确，首版先做确定性 |
| 五维诊断 + 负分制 | ✅ 保留核心思想 | 简化为「正确性门禁 + 过程扣分」 |
| 多 Trial + 稳定性聚合 | ✅ 保留 | 识别 flaky 行为的必要手段 |
| Capability vs Regression 套件 | ✅ 保留概念 | 首版只做 Regression，Capability 后续扩展 |

## 2. 总体策略：从简单到复杂

采用**渐进式落地**，每阶段都有可交付的产出，不追求一次性完备。

```
Phase 0: 手动 Eval（已具备条件，0.5 天）
   ↓
Phase 1: Trace 导出 + Token 落库（1-2 天）
   ↓
Phase 2: 最小自动评分（确定性断言）（2-3 天）
   ↓
Phase 3: 多 Trial + 回归门禁（1-2 天）
   ↓
Phase 3.5: Record/Replay 确定性回归（按需，1-2 天）
   ↓
Phase 4: LLM Rubric + Bad Case 管理（按需）
   ↓
Phase 5: 产品化平台（长期目标）
```

## 3. 分阶段实施

### Phase 0：手动 Eval（立即开始）

**目标**：建立对 Agent 失败模式的直觉，为自动化用例设计提供输入。

**做法**：
1. 写 10-20 条真实场景 prompt（覆盖正例、负例、边界、异常）
2. 手动运行，观察 `session_events` / `tool_executions` 表
3. 记录发现的问题：工具选对了吗？有没有死循环？结果对吗？

**产出**：
- `docs/eval/manual-test-cases.md`：人工测试用例集
- `docs/eval/failure-patterns.md`：观察到的失败模式分类

> **提示**：手动用例建议直接按 Phase 2 的 YAML 格式（见 2.1）记录草稿存入
> `eval/cases/`（哪怕暂时不跑），Phase 2 加载器一到位即可直接复用，
> 避免二次迁移。

---

### Phase 1：Trace 导出 + Token 落库（1-2 天）

**目标**：让单次运行的完整轨迹可导出、可查看。

**技术方案**：

#### 1.1 Token Usage 持久化（补齐缺口）

当前 `infra-ai/model/TokenUsage.java` 已解析、`ModelResponse.usage()` 接口已就位
（`Text` / `ToolCalls` 两个变体均携带 usage），但**未落库**。

真正的缺口有两点：

1. **跨 model call 聚合**：一次 run 会有多次模型调用（ReAct 循环）。
   注意：`HookContext.attributes` 只在**同一挂载点对**（Before→After）内共享，
   kernel 对每次 model call 新建 context，POST_TURN 又是独立 context——
   跨调用累加无法走 attributes。因此采用**逐次增量 UPDATE**：
   `AFTER_MODEL_CALL` 每次直接 `SET input_tokens = input_tokens + ?`，
   hook 保持完全无状态，也无需改 kernel。
2. **落库字段**：`agent_runs` 直接加四列而非 JSON，方便 SQL 聚合分析
   （`cache_write_tokens` 当前 OpenAI 兼容层恒为 0，为 Anthropic 原生
   缓存预留，加列免二次迁移）：

```sql
ALTER TABLE agent_runs ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN cache_read_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_runs ADD COLUMN cache_write_tokens BIGINT NOT NULL DEFAULT 0;
```

```java
// Hook 实现：AFTER_MODEL_CALL 逐次增量落库（无状态，无跨调用聚合）
@Bean
AgentHook tokenUsagePersistence(AgentRunRepository runRepository) {
    return AFTER_MODEL_CALL 挂载点 → {
        ModelResponse response = context.modelResponse();  // 失败路径为 null
        if (response == null || TokenUsage.ZERO.equals(response.usage())) return;
        runRepository.addTokenUsage(context.runId(), response.usage());
    };
}
```

#### 1.2 Trace 导出（Service + HTTP 端点）

核心逻辑放 `TraceService`，出口做成 HTTP 端点（不做 CLI——项目形态是
API-first，且 EvalRunner 与 kernel 同进程，直接注入 Service 即可）：

```
TraceService.export(runId)  ← 组装（session_events / tool_executions / agent_runs）+ 打码
   ├─ TraceController: GET /api/runs/{runId}/trace  ← 人工排查（复用 V9 认证 + owner 校验）
   └─ EvalRunner 进程内直接注入调用                  ← Phase 2 失败自动导 trace
```

- 端点权限对齐现有 12 类端点的 owner 校验模式（越权 404）
- Phase 6 产品化 Dashboard 的「查看 trace」以此端点为直接前置

导出内容（JSON 格式）：
```json
{
  "runId": "...",
  "agentId": "...",
  "input": "用户输入",
  "events": [
    {"type": "model_call", "timestamp": "...", "inputTokens": 100, "outputTokens": 50},
    {"type": "tool_call", "timestamp": "...", "tool": "read_file", "args": "...", "result": "..."}
  ],
  "output": "最终回答",
  "durationMs": 5000,
  "totalTokens": 150
}
```

**产出**：
- Token usage 持久化实现
- `TraceService` + `GET /api/runs/{runId}/trace` 端点
- 可导出的 JSON Trace 格式定义

> **注意（打码）**：`arguments_json` / `result_content` 可能含密钥、用户数据。
> Trace 导出应复用 configs 三级继承链已有的打码规则，敏感字段导出前脱敏。

---

### Phase 2：最小自动评分（2-3 天）

**目标**：基于 YAML 用例文件，自动运行并评分。

#### 2.1 用例文件格式（YAML）

```yaml
# eval/cases/read-file-basic.yaml
id: read-file-01
name: 读取文件并总结
# 环境夹具：运行前预置 workspace 文件；每个 Trial 运行前重置
fixture:
  files:
    - path: hello.txt
      content: "hello world, this is a test file."
# turns 数组：首版只支持单元素，格式预留多轮扩展空间
turns:
  - input: "请读取 workspace/hello.txt 并总结内容"
expected:
  tools:
    required: ["read_file"]
    forbidden: ["write_file", "exec"]
  output:
    must_contain: ["hello", "world"]   # 忽略大小写与多余空白后匹配
    must_not_contain: []
    must_match: []                     # 正则，用于格式类断言
  # 文件系统终态断言：write 类用例必须验证副作用，不能只看 tool_executions
  files: []
  # 示例：
  # files:
  #   - path: output.txt
  #     must_contain: ["done"]
  max:
    tool_calls: 3
    latency_ms: 10000
scoring:
  mode: deduction
  max_score: 100
  pass_threshold: 80
  result_incorrect_penalty: 100
```

**关键字断言的适用边界**：`must_contain` 对中文同义表达很脆弱，只适合
**事实性锚点**（文件名、数字、明确出现的原文词）。语义质量（总结得好不好、
语气对不对）留给 Phase 4 的 LLM Rubric，不要试图用关键词硬凑。

#### 2.1.1 环境夹具与隔离（Phase 2 的前置条件）

自动化评分能否成立，取决于每次运行的环境是否干净、可比。三条硬性规则：

1. **Fixture 生命周期**：每个 Trial 运行前，按 `fixture.files` 重建 workspace
   （先清空再写入）。Trial 2 绝不能看到 Trial 1 的写入残留，否则
   `forbidden`/`files` 断言全部失真。
2. **会话隔离**：每个 Trial 使用**全新 sessionId**。否则会话历史回灌，
   Trial 2 的模型输入被 Trial 1 污染，评分不可比。副产品：结合 V8 的
   会话级 FIFO 队列，独立 session 的 case 天然可并发运行，缩短 suite 时长。
3. **数据隔离**：`@SpringBootTest` 跑真实 kernel 会写 `sessions` /
   `session_events` / `agent_runs` 等表。eval 使用独立 profile
   （独立 DB 文件，或专用 `eval-` 前缀 userId/agentId + suite 结束后清理），
   不污染开发/生产数据。

#### 2.1.2 被评测配置 Pin 住（可比性前提）

V9 之后 agent 的 model / system prompt / 工具启停走 configs 三级继承链，
环境里改一下配置，eval 分数就不可比了。因此：

- suite 显式声明 eval 专用 agent 的**完整配置**（model id、temperature、
  启用工具集），运行时以此覆盖，不依赖环境中的继承链取值
- 报告中记录 **config snapshot + git commit hash**，Phase 3 的「回归」
  对比才有依据

```yaml
suite:
  name: 核心功能回归套件
  agent_config:           # Pin 住被评测配置
    model: kimi-k2.5
    temperature: 0
    tools_enabled: [read_file, write_file, list_dir]
  cases: [read-file-01, ...]
```

#### 2.2 评分器实现

基于现有测试基建，实现以下确定性评分器：

| 评分器 | 检查内容 | 数据来源 |
|--------|----------|----------|
| `ToolContractGrader` | 必须/禁止工具、调用次数 | `tool_executions` 表 |
| `OutputContainsGrader` | 输出包含/不包含/正则（normalize 后） | `AgentRunResult` |
| `FileStateGrader` | 文件系统终态（副作用断言） | workspace 目录 |
| `LatencyBudgetGrader` | 延迟是否超标 | Hook 计时或 `agent_runs` |
| `TokenBudgetGrader` | Token 是否超标 | Phase 1 落库的 usage |

**评分规则（简化负分制）**：
- 起点 100 分
- 结果错误（工具调用严重违规）扣 100 → 0 分，失败
- 过程违规（多余工具、超时）每项扣 10-20 分
- 80 分通过

#### 2.3 Runner 实现

复用现有 `@SpringBootTest` 基建：

```java
@SpringBootTest
@ActiveProfiles("eval")  // 独立 profile：独立 DB，见 2.1.1
public class EvalRunnerTest {

    @Test
    public void runEvalCases() {
        // 1. 加载所有 YAML 用例
        List<EvalCase> cases = loadCases("eval/cases/");

        // 2. 对每个用例运行 Agent
        for (EvalCase c : cases) {
            setupFixture(c);                       // 重建 workspace（2.1.1）
            String sessionId = newEvalSessionId(); // 每次全新 session（2.1.1）

            AgentRunResult result = agentKernel.run(
                new AgentRunCommand(..., c.getInput(), ...),
                eventSink
            );

            // 3. 查询 tool_executions 等表获取轨迹
            List<ToolExecution> tools = toolExecutionRepository.findByRunId(...);

            // 4. 运行评分器
            Score score = grader.grade(c, result, tools);

            // 5. 输出报告
            report.add(c.getId(), score);
        }

        // 6. 断言整体通过
        assertThat(report.getPassRate()).isGreaterThan(0.8);
    }
}
```

**Runner 健壮性要求**（真实 LLM 环境下必备）：

- **per-case 硬超时**：agent 死循环或供应商挂起时，单 case 超时终止
  （`CompletableFuture.get(timeout)` 或等价机制），不能拖垮整个 suite
- **单 case 异常隔离**：任何 case 抛异常记为 `ERROR` 状态继续跑下一个，
  suite 永远跑完并出完整报告
- **失败自动导 Trace**：case 失败/ERROR 时自动调用 Phase 1 的
  `TraceExporter`，将 trace JSON 附入报告——排查失败无需二次复现
  （这也是 Phase 1 与 Phase 2 的衔接点）

**产出**：
- YAML 用例格式定义（含 fixture / files 断言 / turns 预留）
- Fixture 生命周期管理 + eval profile 隔离
- 5 个确定性评分器实现
- `EvalRunner` 测试类（含超时/异常隔离/失败导 trace）
- 控制台/JSON 报告输出

---

### Phase 3：多 Trial + 回归门禁（1-2 天）

**目标**：识别不稳定行为，建立 CI 门禁。

#### 3.1 多 Trial 运行

每个用例运行 N 次（默认 3 次）：

```yaml
suite:
  name: 核心功能回归套件
  trials_per_case: 3
  cases: [read-file-01, read-file-02, ...]
```

聚合指标：
- `pass_rate` = 成功 Trial 数 / 总 Trial 数
- `correctness_rate` = Outcome 正确 Trial 数 / 总 Trial 数（严格，必须 100%）
- `mean_score` = 平均分
- `score_stddev` = 分数标准差（识别不稳定用例）

#### 3.2 回归门禁规则

```yaml
release_gate:
  require_correct_outcome: true      # 所有 Trial 必须结果正确
  min_case_score: 80                 # 单用例平均分 >= 80
  min_pass_rate: 1.0                 # 通过率 100%
  max_new_failures: 0                # 不允许新增失败（相对基线）
```

#### 3.2.1 回归基线（Baseline）机制

`max_new_failures: 0` 需要「上一次结果」作为参照，方案必须定义基线存放与更新：

- **存放**：`eval/baseline.json` 提交进 git，记录每个 case 的期望状态
  （pass / known-fail）+ 生成时的 config snapshot + commit hash
- **门禁判定**：本次运行结果与 baseline diff——baseline 里 pass 的 case
  失败 = 回归（门禁挂）；baseline 里 known-fail 的 case 仍失败 = 不挂
  （已知问题，不阻塞）；known-fail 变 pass = 提示更新基线
- **更新**：基线更新走 PR 评审，与代码变更同评——防止「悄悄放宽标准」

#### 3.3 CI 集成

**现实约束**：每次 push 都跑真实 LLM 调用，存在三个问题——慢、贵、
供应商侧抖动会让 `min_pass_rate: 1.0` 的门禁频繁误伤。因此 CI 策略为：

- **PR / push**：只跑不调模型的单测（现有测试体系），eval 不进这条路径
- **Eval 触发**：nightly 定时 + 手动 `workflow_dispatch` + PR 打
  `run-eval` label 时触发
- **API key** 走 GitHub secrets；workflow 中限制并发（避免供应商限流）
  并设 job 级超时兜底
- 供应商抖动导致的失败：先重试该 Trial 一次再判定，报告中标注 retried

```yaml
# .github/workflows/eval.yml
name: Eval Regression
on:
  schedule:
    - cron: '17 19 * * *'   # nightly
  workflow_dispatch:
  pull_request:
    types: [labeled]         # label == run-eval 时跑
jobs:
  eval:
    if: github.event_name != 'pull_request' || github.event.label.name == 'run-eval'
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - name: Run Eval
        env:
          LLM_API_KEY: ${{ secrets.EVAL_LLM_API_KEY }}
        run: ./mvnw test -Dtest=EvalRunnerTest -Pspring.profiles.active=eval
      - name: Upload Report
        uses: actions/upload-artifact@v4
        with:
          name: eval-report
          path: target/eval-report.json
```

**产出**：
- 多 Trial Runner
- 回归套件配置 + 门禁逻辑
- `eval/baseline.json` 基线机制
- CI workflow（nightly/手动/label 触发）
- 失败时自动输出具体 Case、Trial、扣分项 + trace

---

### Phase 3.5：Record/Replay 确定性回归（按需，1-2 天）

**目标**：让回归套件摆脱对真实 LLM 的依赖，实现快、免费、100% 确定性的
CI 门禁；真实 LLM 调用降级为 nightly 的「能力验证」。

**做法**：当前代码库尚无 stub `LLMService`，需要补：

1. **Record 模式**：真实运行时把每次 `ModelRequest → ModelResponse` 序列化
   存入 case 目录（`eval/cases/<id>/recordings/`）
2. **Replay 模式**：`ReplayLLMService implements LLMService`，按请求序
   （或请求指纹）回放录制的 `ModelResponse`，不发网络请求
3. Replay 下跑完整 kernel + 真实工具执行 + 全部评分器——验证的是
   **kernel 行为与工具链**的回归，而非模型能力
4. 模型/prompt 变更后录制失配 → 重新 record 并走 PR 评审更新

**适用边界**：replay 验证不了「换 prompt 后模型是否还选对工具」——
那属于真实 LLM eval（nightly）。两条轨道各管一半：
- **Replay（PR 门禁）**：kernel / 工具 / hook / 持久化逻辑回归，确定性 100%
- **真实 LLM（nightly）**：模型行为回归 + 能力评测

**产出**：
- `ReplayLLMService` + record 模式开关
- 录制文件格式与失配检测
- PR 门禁切换到 replay 轨道

---

### Phase 4 及以后（按需）

| 阶段 | 内容 | 触发条件 |
|------|------|----------|
| Phase 4 | LLM-as-Judge Rubric、语义质量评分 | 确定性评分无法满足需求时 |
| Phase 5 | Bad Case 管理、一键转回归用例 | Bad Case 积累到一定数量 |
| Phase 6 | 产品化 Dashboard、Web 界面 | 团队规模扩大，需要可视化 |
| Phase 7 | Capability 套件、能力爬坡追踪 | 需要衡量 Agent 能力提升时 |

## 4. 数据模型（首版简化版）

不复用 FastClaw Spec 的 9 张表，首版复用现有表 + 最小扩展：

```sql
-- 复用现有表
-- sessions, session_events, agent_runs, tool_executions

-- 最小新增：Eval 用例定义（可存文件或简单表）
CREATE TABLE eval_cases (
    id VARCHAR(64) PRIMARY KEY,
    suite_id VARCHAR(64),
    definition_json TEXT,  -- YAML 转 JSON
    priority VARCHAR(8),   -- P0/P1/P2
    tags TEXT              -- JSON 数组
);

-- 可选：Eval 运行记录（如果不存文件）
CREATE TABLE eval_runs (
    id VARCHAR(64) PRIMARY KEY,
    suite_id VARCHAR(64),
    started_at TIMESTAMP,
    config_json TEXT,
    report_json TEXT       -- 完整报告
);
```

## 5. 任务清单（Ready for Dev）

### Phase 1（✅ 已完成，2026-07-19）
- [x] TokenUsage 持久化：`AFTER_MODEL_CALL` 逐次增量落库（V7 迁移加 agent_runs 四列，`TokenUsageHook`）
- [x] `TraceService` + `GET /api/runs/{runId}/trace` 端点（含敏感字段打码、owner 校验越权 404）
- [x] 定义 JSON Trace 格式（`TraceVO`：run 元信息 + token 四列 + durationMs + 工具事件序列）

### Phase 2（下周）
- [ ] YAML 用例格式定义 + 加载器（fixture / turns / files 断言）
- [ ] eval profile（独立 DB）+ fixture 生命周期 + session 隔离
- [ ] 实现 5 个确定性评分器
- [ ] `EvalRunner` 测试类（per-case 超时、异常隔离、失败导 trace）
- [ ] 控制台/JSON 报告（含 config snapshot + commit hash）

### Phase 3（第三周）
- [ ] 多 Trial Runner（每 Trial 重建 fixture + 新 session）
- [ ] 回归套件配置 + 门禁逻辑
- [ ] `eval/baseline.json` 基线生成与 diff 判定
- [ ] CI workflow（nightly / 手动 / label 触发，供应商抖动重试）

### Phase 3.5（按需）
- [ ] `ReplayLLMService` + record 模式
- [ ] 录制文件格式 + 失配检测
- [ ] PR 门禁切到 replay 轨道

## 6. 与 Hook 机制的关系

新实现的 Hook 机制是 Eval 的最佳埋点：

| Hook 点 | Eval 用途 |
|---------|-----------|
| `AFTER_MODEL_CALL` | 收集 Token usage、记录模型响应 |
| `AFTER_TOOL_CALL` | 记录工具调用结果、检查工具契约 |
| `POST_TURN` | 记录运行摘要、触发评分 |

建议实现一个 `EvalTraceHook`，将所有事件写入结构化日志或内存队列，供 Runner 后续分析。

## 7. 参考

- FastClaw 完整方案：[ai-agent-evaluation-system-spec.md](./ai-agent-evaluation-system-spec.md)
- 当前项目 Hook 实现：`agent-core/src/main/java/ai/openagent/agent/hook/`
