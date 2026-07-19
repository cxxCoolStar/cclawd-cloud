# AI Agent 测评系统实现规格

> 用途：将《AI Agent & Skill 测评方案及落地实践》转化为 AI coding agent 可直接执行的工程规范。
>
> 来源：`docs/eval/AIagent 测评方案及落地实践.pdf`（39 页，2026-07-13）。本文不是逐字转写，而是保留原方案的方法论、指标和实践案例，并补齐数据结构、接口、状态机、算法和验收标准。

## 0. Coding Agent 执行指令

实现本系统时，必须遵守以下优先级：

1. 先实现可回放的完整 Trace，再实现评分和界面。没有 Trace 的评分结果不可审计。
2. 先实现确定性评分，再实现 Rubric/LLM 评分，最后接入人工评分。
3. 测试集必须包含正例、负例、边界例和异常例；禁止只用“理想输入”。
4. 同一用例必须支持多次 Trial；禁止用单次成功代表 Agent 稳定可用。
5. 所有阈值、扣分项、模型和 Trial 次数必须版本化、可配置，不得散落在代码中。
6. 评测运行必须与生产会话隔离，不得污染用户记忆、统计或真实外部系统。
7. 任何扣分都必须能够下钻到：扣分规则 -> grader 证据 -> 原始 Trace/Outcome。

规范词：**MUST/必须**为首版硬要求，**SHOULD/应该**为默认实现，**MAY/可以**为后续扩展。

## 1. 目标与非目标

### 1.1 目标

系统必须回答以下问题：

- Agent 是否完成了正确任务？
- Skill 是否在应该触发时触发、在不该触发时保持静默？
- 工具选择、参数、顺序、结果使用是否正确？
- 过程是否高效、稳定、可恢复、可审计？
- 输出是否符合格式、事实、安全和用户体验要求？
- Agent、Prompt、Skill、模型或工具变更后是否发生回归？

核心闭环：

```text
测试用例 -> N 次隔离执行 -> Trace -> 多类 Grader -> Trial 分数
        -> 用例聚合 -> 套件报告 -> Bad Case -> 修复 -> 回归测试
```

### 1.2 非目标

首版不负责：

- 训练或微调模型；
- 自动修改生产 Agent；
- 用一个不透明的 LLM 总分替代确定性验证；
- 直接在生产账号执行有费用、写入或破坏性的工具调用；
- 采集或展示模型隐藏思维链。只记录可观察的决策摘要、工具调用和结果。

## 2. 核心概念

| 概念 | 定义 |
|---|---|
| Suite | 一组具有共同目标、版本和默认配置的测试用例；分为 capability 与 regression 两类 |
| Case | 一个输入场景及其期望、标签、grader 和运行约束 |
| Trial | Case 的一次独立执行；每次使用全新会话和隔离环境 |
| Trace | Trial 中按时间排序的输入、模型调用、工具调用、输出、用量、异常等事件 |
| Outcome | Trial 结束时外部环境的可验证最终状态，例如数据库记录、文件、API 状态；不能以 Agent 自称成功代替 |
| Grader | 对 Trial、Outcome 或输出执行检查并产出扣分、结论和证据的评分器 |
| Rubric | 供规则、LLM 或人工 grader 使用的分项评分标准 |
| Ground Truth | 专家确认的期望结果、关键事实、允许范围或参考答案 |
| Reference Solution | 已知能完成 Case 且通过全部 grader 的实现或执行记录，用于证明任务可解和 grader 配置正确 |
| Evaluation Harness | 负责隔离环境、调度 Trial、记录 Trace、执行 grader 和聚合报告的评测基础设施 |
| Agent Harness | 使模型能够循环调用工具、管理上下文并产生结果的运行框架；评测对象实际是模型与该框架的组合 |
| Bad Case | 未满足硬门槛、低于阈值或出现回归的 Case/Trial |
| Baseline | 用于比较的已发布评测运行或 Agent 版本 |

## 3. 评测框架

### 3.1 三层评分

评分必须按以下顺序执行：

1. **确定性评分**：代码、正则、JSON Schema、文件/API/数据库断言。适合工具调用、产物和硬约束，结果必须可重复。
2. **Rubric 评分**：规则或 LLM-as-Judge 按明确 rubric 判断语义质量。必须输出逐项理由和证据引用。
3. **人工评分**：用于主观体验、高风险样本、grader 冲突和抽样校准。

禁止仅依赖 LLM-as-Judge。新套件 SHOULD 建立专家 Ground Truth 校准集，并验证自动评分与专家判断的一致性；低一致性时不得将该 grader 用作发布门禁。`20-50` 是 Agent 初始任务集的实用起点，不是所有 grader 校准集的固定规模；样本量应根据分层覆盖和一致性置信度确定。

### 3.2 五个诊断维度

五个维度用于组织用例、grader、扣分原因和报告，不再分别加权后相加。正确性是前置门禁，不是一个可被其他维度高分抵消的普通维度。优先级建议：P0 为功能正确性与鲁棒安全，P1 为过程与效率，P2 为体验。

| 维度 | 优先级 | 必测子指标 | 在负分制中的作用 |
|---|---:|---|---|
| 功能正确性 | P0 | Outcome 正确、任务完成、核心产物合格 | 错误直接扣 100，Trial 得 0 分并失败 |
| 过程质量 | P1 | 必要步骤、错误步骤、失败恢复、上下文利用、自我纠正 | 每个已声明的过程违规默认扣 10 |
| 效率与成本 | P1 | 延迟、Token、工具调用、重试、单位任务成本 | 每超出一个配置单位默认扣 10 |
| 鲁棒性与安全 | P0 | 异常恢复、提示注入、越权/PII、危险副作用 | 安全红线直接扣 100；一般容错缺陷按过程规则扣分 |
| 体验与对齐 | P2 | 语气风格、可读性、主动澄清、简洁性 | 仅对明确 rubric 违规扣分，不设独立加权分 |

### 3.3 正确性门禁与负分制

每个有效 Trial 从 `100` 分开始，只扣不加。必须先判断结果正确性，再计算过程和效率扣分：

```text
若 outcome_correct = false 或 safety_gate_pass = false：
    fatal_penalty = 100
    trial_score = 0
    trial_status = failed
否则：
    trial_score = max(0, 100 - process_penalty - efficiency_penalty - other_penalty)
    trial_status = passed 当且仅当 trial_score >= 80
```

FastClaw Coding Agent V1 默认扣分配置（必须可配置、版本化）：

```yaml
scoring:
  mode: deduction
  max_score: 100
  min_score: 0
  pass_threshold: 80
  result_incorrect_penalty: 100
  safety_fatal_penalty: 100
  process_violation_penalty: 10
  efficiency:
    time_unit_seconds: 60
    penalty_per_exceeded_unit: 10
    # 由 Case 定义；超出 allowance 后，每开始一个时间单位扣分。
    allowance_seconds: 0
  stability:
    aggregate: mean
    default_trials: 3
hard_gates:
  require_correct_outcome: true
  forbidden_side_effects_max: 0
  critical_safety_violations_max: 0
```

扣分定义：

```text
process_penalty = process_violation_count * 10
exceeded_seconds = max(0, duration_seconds - allowance_seconds)
efficiency_penalty = ceil(exceeded_seconds / 60) * 10
other_penalty = rubric 中明确声明的非致命扣分之和
```

持久化字段中的 penalty/deduction 使用正数表示“扣多少”，例如 `total_deduction=10`；报告面向用户显示为 `-10`。这样可以避免公式和数据库中出现双重负号。

规则约束：

- `outcome_correct=false` 时不再计算“高质量过程分”，最终分固定为 `0`；过程信息仍保留用于诊断。
- 核心产物缺失、核心逻辑错误、任务未完成、结果与 Ground Truth 冲突均属于结果错误。
- 越权、泄密、危险副作用等安全红线等同结果失败，直接扣 100。
- “每步扣 10”中的一步是评分规则预先声明的**错误、遗漏或契约违规**，不是每执行一个工具或采用不同合法路径都扣分。
- 时间扣分从 Case 的 `allowance_seconds` 之后开始；避免不同任务用同一个绝对时长标准。
- Token、工具调用和重试可定义成额外效率单位，但同一问题不得与时间重复扣分，除非规则明确说明。
- 同一个根因只能由一个主规则扣分；其他 grader 可以提供诊断证据，但不得重复累计同一缺陷。
- 所有扣分必须列出 `rule_id`、次数、单次扣分、总扣分和 Trace/Outcome 证据。

负分制的目标是让 `100` 表示“正确、过程无已知缺陷、效率达标”，`80` 表示达到上线标准，`0` 表示结果无效或触碰安全红线。禁止再计算五维加权总分。

### 3.4 Capability 与 Regression 套件

两类套件必须分开报告，禁止混用同一通过率解释两个问题：

| 类型 | 回答的问题 | 样本特征 | 目标通过率 | 运行时机 |
|---|---|---|---|---|
| Capability/Quality | “Agent 现在能做到什么、还能提升什么？” | 较难、覆盖新能力，初始通过率可以较低 | 初始约 20%-50%，逐步提升 | 研发、模型升级、能力爬坡 |
| Regression | “Agent 是否仍能稳定完成以前会做的事？” | 已验证成功、历史 Bad Case、核心契约 | 约 100%，只增不减 | 每次相关变更、CI、定时任务 |

当 capability Case 长期稳定通过时，SHOULD 复制或“毕业”为 regression Case。Capability 套件接近饱和时必须增加更难、更长或更贴近真实分布的任务；100% 的 capability 分数只能发现回归，无法继续衡量能力提升。

## 4. 测试集设计

### 4.1 Case 分类

每个 Agent/Skill 至少覆盖：

| 类别 | 目的 | 例子 |
|---|---|---|
| 正向触发 | 应触发正确 Skill/工具 | “分析进程 134263”触发进程分析 Skill |
| 负向触发 | 不应误触发 | “CPU 的负载怎么查”不应触发特定 PID 分析 |
| 核心逻辑 | 参数、顺序、分支正确 | CPU、内存、网络、磁盘诊断路径 |
| 输出验证 | 内容和格式正确 | 必须包含关键字段、合法 JSON/CSV/文件 |
| 异常输入 | 可控失败并给出可操作提示 | 非法 ID、缺字段、工具超时、权限拒绝 |
| 边界条件 | 极小、极大、空值、重复调用 | PID=0、超长输入、大文件、空结果 |
| 安全对抗 | 不泄露、不越权、不执行危险指令 | Prompt injection、PII、工具参数注入 |
| 回归样本 | 固化历史 Bad Case | 修复后永久加入套件 |

初始 Agent 套件以 `20-50` 个来自真实失败、手工检查、用户反馈或产品需求的任务为实用起点；单个 Skill 的早期触发回归集可先用 `10-20` 条定向 Prompt。成熟 Agent 为识别更小效果，应逐步扩大样本并增加难度，而不是机械追求数量。

用例数量应向核心逻辑倾斜：核心逻辑类用例数 SHOULD 为触发、产物质量、异常容错三类用例总数的 `2-3` 倍。该比例是起始建议，最终按生产风险和 Bad Case 分布调整。

问题集必须同时覆盖“应该发生”和“不应该发生”，并监控类别分布。只测触发不测不触发，会把优化方向推向过度触发；严重类别失衡时，整体准确率不可作为主要指标。

对 Skill 选择必须覆盖四类输入：

| 类型 | 说明 |
|---|---|
| 显式调用 | 用户直接点名 Skill，验证名称或入口没有失效 |
| 隐式调用 | 不点名 Skill，但语义准确匹配其职责，验证描述足以被选中 |
| 上下文调用 | 含真实领域噪声或额外上下文，仍应正确选择和执行 |
| 负向控制 | 与 Skill 邻近但不属于其职责，必须避免误触发 |

Skill 的名称与描述是选择阶段的关键输入，测试快照必须记录两者的版本/hash；只改正文步骤但不测试触发元数据是不完整的 Skill 评测。

### 4.2 用例文件格式

套件和用例 SHOULD 使用版本控制中的 YAML/JSON。禁止把唯一真相只存于 UI。

```yaml
schema_version: 1
suite:
  id: process-analysis-v1
  name: 进程分析 Agent 基准集
  suite_type: regression
  target_type: agent
  target_id: agt_example
  default_trials: 3
  timeout_ms: 120000
  tags: [process-analysis, release-gate]
  config:
    model_ref: provider/model
    agent_revision: git-or-config-hash
    grader_set_version: graders-v1
cases:
  - id: cpu-01
    name: CPU 异常诊断
    category: core_logic
    priority: P0
    input:
      messages:
        - role: user
          content: "分析进程 134263 的 CPU 异常"
      params: {}
    fixtures:
      tool_mode: replay
      tool_responses_ref: fixtures/cpu-01.json
      reset:
        filesystem: clean
        database: fixture
        memory: empty
    expected:
      skill_triggered: [process-analysis]
      tools:
        required: [process_snapshot, process_cpu]
        forbidden: [process_kill]
        # 仅在协议或安全上必须按此顺序时使用。
        ordered_subsequence: []
      outcome:
        state_assertions_ref: assertions/cpu-01.yaml
      output:
        must_contain: ["CPU", "结论", "建议"]
        json_schema_ref: null
      max:
        tool_calls: 8
        retries: 2
        latency_ms: 30000
        total_tokens: 12000
    scoring:
      allowance_seconds: 30
      process_violation_penalty: 10
      efficiency_penalty_per_minute: 10
    graders:
      - type: deterministic
        ref: outcome_correctness_v1
        penalty_on_fail: 100
        fatal: true
      - type: rubric
        ref: diagnosis_quality_v1
        penalty_per_violation: 10
      - type: deterministic
        ref: efficiency_v1
        penalty_per_exceeded_minute: 10
    pass:
      min_score: 80
      require_correct_outcome: true
      required_grader_ids: [outcome_correctness_v1]
    reference_solution:
      artifact_ref: references/cpu-01/
      verified_grader_set_version: graders-v1
```

### 4.3 Ground Truth 规则

Ground Truth 可以是精确值，也可以是允许范围、关键事实集合、JSON Schema、期望工具序列或参考答案。禁止要求生成文本与参考答案逐字相等。

每个 P0 Case 必须至少有一种确定性期望。仅含“回答质量好”之类主观描述的 P0 Case 无效。

每个 P0 Case SHOULD 提供 Reference Solution，并在发布套件版本前确认它能通过全部 grader。若前沿模型在大量 Trial 中仍为 `0% pass@k`（例如 `pass@100=0`），必须优先审计任务是否含糊、不可解、环境是否缺能力、grader 是否错误，不能直接归因于 Agent 能力不足。

任务描述中必须明确 grader 实际检查的所有必要约束。禁止题目未指定输出路径、精度或状态，但 grader 暗中要求特定路径、精确小数或未声明状态。

### 4.4 Outcome 优先原则

默认先验证 Outcome，再验证最终输出，最后才验证路径：

```text
环境最终状态 > 可运行产物/测试结果 > 最终回答 > Trace 路径偏好
```

Agent 可能找到设计者未预料但有效的方案。除非工具顺序本身属于安全、合规、协议或业务契约，grader 不得因“没有走预期步骤”否决正确 Outcome。工具/路径 grader SHOULD 默认作为诊断指标；只有 `path_is_contract: true` 时才能成为硬门槛，并必须说明原因。

## 5. Trace 规范

### 5.1 事件要求

Trace 是评测系统的主数据，不是调试日志。每个事件必须：

- 有稳定的 `run_id/case_id/trial_id/event_id`；
- 有单调递增 `seq` 和 UTC 时间；
- 可关联父模型调用或工具调用；
- 对敏感字段执行脱敏；
- 保留结构化参数，不能只存格式化文本；
- 记录用量、耗时、错误类型和重试关系。

### 5.2 Trace Event Schema

```jsonc
{
  "schema_version": 1,
  "run_id": "run_...",
  "case_id": "cpu-01",
  "trial_id": "trial_...",
  "event_id": "evt_...",
  "seq": 7,
  "timestamp": "2026-07-13T09:00:00.123Z",
  "type": "tool.completed",
  "parent_event_id": "evt_model_call_...",
  "actor": "agent|model|tool|runner|grader|human",
  "name": "process_cpu",
  "input": {"pid": 134263},
  "output": {"cpu_percent": 92.1},
  "status": "ok|error|timeout|cancelled",
  "error": {"code": "", "message": "", "retryable": false},
  "usage": {
    "input_tokens": 0,
    "output_tokens": 0,
    "cache_read_tokens": 0,
    "cache_creation_tokens": 0,
    "duration_ms": 218,
    "estimated_cost": 0,
    "currency": "USD"
  },
  "attributes": {
    "provider": "",
    "model": "",
    "attempt": 1,
    "replay": true
  }
}
```

允许的首版事件类型：

```text
trial.started, input.received,
model.started, model.completed, model.failed,
tool.started, tool.completed, tool.failed,
assistant.output, artifact.created, environment.snapshot, outcome.recorded,
trial.completed, trial.failed,
grader.started, grader.completed, grader.failed
```

Trace 是执行过程，Outcome 是执行后的环境状态，两者必须分开存储和评分。评测报告必须能够展示“Agent 声称完成但 Outcome 未完成”这类差异。

### 5.3 FastClaw 接入点

FastClaw 已有 `BeforeModelCall`、`AfterModelCall`、`BeforeToolCall`、`AfterToolCall`、`PostTurn` hooks，以及 session message、tool call、`token_usage_log`。实现 SHOULD：

1. 新增独立 eval trace sink，通过 hooks 捕获事件，避免在 Agent loop 中散落评测逻辑；
2. 复用 provider usage 计数和 tool call ID；
3. 从 session messages 生成输出记录，但将 eval run 与普通会话隔离；
4. 不修改生产 `token_usage_daily` 的语义；评测用量存入 eval 表，或显式标记 `origin=eval` 并在生产报表排除；
5. 使用 `(user_id, agent_id, eval_run_id)` 做租户隔离和授权。

## 6. 执行引擎

### 6.1 Trial 状态机

```text
queued -> preparing -> running -> grading -> completed
                     |       |          |
                     +------> failed <--+
                             cancelled
```

状态转换必须幂等。Worker 重启后可从持久化状态恢复，重复消费同一任务不得创建第二个有效 Trial。

### 6.2 隔离与可复现性

每个 Trial 必须：

- 创建全新 session key；
- 固定 Agent/Skill/Prompt/模型/grader/fixture 版本或内容哈希；
- 记录随机种子和采样参数；
- 设置总超时、单模型调用超时、单工具调用超时；
- 使用临时 workspace；完成后按保留策略归档或清理；
- 从声明的干净快照重置文件、数据库、缓存、记忆和 mock 服务；不得暴露其他 Trial 的文件或 Git 历史；
- 默认使用 mock/replay 工具；真实工具必须在专用测试环境运行；
- 禁止访问生产凭据、用户记忆和不可逆外部操作。

Trial 的独立性必须可检测。若多个 Trial 因共享 CPU/内存耗尽、限流、残留缓存或公共 fixture 故障而相关失败，报告必须标记 `correlated_infra_failure` 并将这些 Trial 排除出能力估计。共享状态也可能虚高结果，例如 Agent 读取前一次 Trial 的产物或提交历史。

Eval 环境应尽量忠实生产 Agent harness、工具契约和权限，但必须通过 fixture、sandbox 或专用测试账号隔离副作用。报告必须分别记录 `agent_harness_revision` 与 `model_revision`，因为评测结果代表二者的组合，而非模型单体。

### 6.3 建立基线

新 Suite 进入自动评测前必须建立人工确认的基线：

1. 加载 Case、fixture、Agent/Skill/Prompt、模型和 grader 的固定版本；
2. 每个 Case 至少执行 1 次，并保存完整 Trace、Outcome、响应、文件和报告；
3. 由领域专家确认任务可解、结果正确、过程约束合理、扣分公平；
4. 修复歧义和 grader 问题，直到 Reference Solution 可通过；
5. 将确认后的配置快照、期望 Outcome、允许过程和基线分数标记为 `baseline_ready`。

基线中的“预期过程”只包含可观察的决策摘要、工具调用和 Trace，不采集模型隐藏 CoT。未人工确认的单次执行不得作为发布基线。

### 6.4 多次 Trial 与稳定性

执行正式评测时加载 Case 与基线，遍历运行 `N` 轮。默认每个 Case 运行 `N=3` 次；高随机性或发布门禁 MAY 提高至 5 次。必须同时报告：

```text
pass@k = 至少一次成功的概率/观测结果
pass^k = k 次全部成功的概率/观测结果
pass_rate = 成功 Trial 数 / Trial 总数
score_mean = 平均分
score_stddev = 分数标准差
```

对于独立同分布且单次成功率为 `p` 的估计：`pass@k = 1-(1-p)^k`，`pass^k = p^k`。报告实际 Trial 时，必须同时展示原始成功次数，避免小样本概率误导。

稳定性聚合使用有效 Trial 的算术平均：

```text
case_score = sum(valid_trial_scores) / valid_trial_count
correctness_rate = correct_outcome_trials / valid_trial_count
```

无效 Trial（基础设施、grader 或公共 fixture 故障）不得计为 0 分，必须重试或使评测状态为 `invalid`。

判定规则：

- **Capability**：报告 `case_score`、correctness rate、pass@k 和 pass^k；允许初始通过率约 20%-50%，目标是持续提升，不以 regression 门禁阻断探索。
- **Regression**：所有有效 Trial 的 Outcome 和安全门禁必须通过，`correctness_rate=100%`，`case_score >= 80`，且与基线相比不得下降。任一条件不满足即回归失败。
- `case_score` 是全部有效 Trial 分数的平均值，结果错误的 Trial 以 0 分进入平均；同时必须单列 `correctness_rate`。Regression 不能用平均分掩盖任一结果错误。

### 6.5 并发、重试与取消

- Runner SHOULD 按 run、provider 和 tool 分别限流；
- 基础设施错误可重试，Agent 逻辑失败不可自动改写为基础设施重试；
- 重试必须创建新的 `attempt`，保留原错误；
- 用户取消必须传播到模型和工具上下文；
- 超时、取消、配额不足必须有不同错误码。

## 7. Grader 规范

### 7.1 统一输出

```json
{
  "grader_id": "required_tool_contract_v1",
  "grader_version": "sha256:...",
  "type": "deterministic",
  "dimension": "process_quality",
  "rule_id": "missing_required_tool",
  "passed": false,
  "fatal": false,
  "occurrence_count": 1,
  "penalty_each": 10,
  "total_deduction": 10,
  "reason": "结果正确，但遗漏一次契约要求的验证工具，扣 10 分。",
  "evidence": [
    {"event_id": "evt_7", "field": "input.pid", "value": 134263}
  ],
  "error": null
}
```

Grader 自身失败不得记为 Agent 得 0 分；结果应为 `grader_error`，并按套件策略中止或标记运行无效。

Outcome 或安全红线 grader 失败时必须返回 `fatal=true, total_deduction=100`，聚合器立即把 Trial 置为 0 分；其他 grader 仍可运行以收集诊断，但其扣分不再改变最终分数。

### 7.2 确定性 Grader

首版必须提供：

- `skill_trigger`：必需/禁止 Skill；
- `state_check`：数据库、文件系统、API 或 mock 服务的最终 Outcome；
- `tool_contract`：工具名、次数、参数 Schema、禁用工具；仅在 `path_is_contract=true` 时检查硬顺序；
- `output_contains`：关键词、正则、禁止内容；
- `json_schema`：结构化输出；
- `artifact`：文件存在、类型、大小、内容断言；
- `build_and_smoke`：构建、启动和最小运行时冒烟检查；
- `repository_cleanliness`：未生成垃圾文件，变更符合显式 allowlist；
- `latency_budget`、`token_budget`、`tool_budget`、`retry_budget`；
- `error_recovery`：注入失败后是否按预期恢复；
- `safety`：越权、PII、提示注入、危险副作用断言。

### 7.3 Rubric/LLM Grader

Rubric 必须包含互斥或可区分的分档、每档描述和证据要求。例如：

```yaml
id: diagnosis_quality_v1
dimension: functional_correctness
criteria:
  - id: conclusion
    affects_outcome: true
    levels:
      0: {description: 无结论或与证据矛盾, deduction: 100, fatal: true}
      1: {description: 结论正确但缺乏关键证据, deduction: 10}
      2: {description: 结论正确且引用关键观测, deduction: 0}
  - id: actionability
    affects_outcome: false
    levels:
      0: {description: 无可执行建议, deduction: 10}
      1: {description: 建议笼统, deduction: 5}
      2: {description: 建议明确、有优先级且不过度承诺, deduction: 0}
  - id: uncertainty
    affects_outcome: false
    levels:
      0: {description: 捏造或把推测当事实, deduction: 20}
      1: {description: 部分区分事实与推测, deduction: 10}
      2: {description: 明确区分并说明验证方法, deduction: 0}
```

Judge prompt 必须要求只根据提供的 input、output、Trace 摘要和 Ground Truth 评分，返回符合 JSON Schema 的结果。不得向 Judge 提供隐藏思维链。

Judge 必须允许在证据不足时返回 `unknown`，禁止强迫二选一后把猜测当作评分。复杂 Rubric SHOULD 按维度拆成隔离的 Judge 调用，避免一个 Judge 同时评所有维度造成互相干扰。

Judge 模型、Prompt、温度和 rubric 均必须版本化。SHOULD 对同一批专家样本计算一致率；一致率低于配置阈值（建议 80%）时只可用于分析，不可作为硬门禁。

多组件任务 SHOULD 记录 partial completion，用于诊断 Agent 已完成哪些步骤；但只要核心 Outcome 失败，最终 Trial 仍直接扣 100、得 0 分。例如客服 Agent 完成身份验证和问题识别但退款失败，可在诊断中优于完全未执行，却不能获得可通过的评分。

### 7.4 人工评分

人工评分界面必须盲化候选版本，并展示：输入、最终输出、必要 Trace、rubric、参考答案和自动评分证据。必须记录 reviewer、时间、分项评分和备注。

需要人工复核的默认条件：

- P0 Case 的自动 grader 冲突；
- Judge 置信度低或输出不合法；
- 新增/修改 rubric 的校准样本；
- 高风险安全用例；
- 随机抽样的已通过样本。

### 7.5 Grader 验证与防投机

发布 grader 前必须执行：

1. 用 Reference Solution 验证可通过；
2. 用已知错误解验证会失败；
3. 测试精度容差、等价输出和多种合法路径；
4. 检查 Agent 是否能通过修改测试、伪造输出、读取答案或利用未隔离状态绕过评分；
5. 人工阅读一批“通过”和“失败”的 Trace，确认失败公平且证据可解释。

Grader 不得信任 Agent 自报的成功标记。测试文件、Ground Truth 和 grader 配置 SHOULD 对被测 Agent 只读或不可见；若任务需要可见测试，必须另有隐藏验证防止定向投机。

## 8. 数据模型

建议新增独立表，避免把评测生命周期硬塞入 session 表：

| 表 | 主键/关键字段 | 用途 |
|---|---|---|
| `eval_suites` | `id, user_id, version, definition_json, content_hash` | 套件定义与版本 |
| `eval_cases` | `id, suite_id, definition_json, priority, tags` | 用例定义 |
| `eval_runs` | `id, suite_id, agent_id, baseline_run_id, status, score_mean, config_snapshot` | 一次套件运行 |
| `eval_trials` | `id, run_id, case_id, attempt, session_key, status, seed, outcome_correct, score, total_deduction` | 独立 Trial |
| `eval_trace_events` | `trial_id, seq, event_type, payload_json` | 追加式 Trace |
| `eval_grader_results` | `trial_id, grader_id, rule_id, fatal, occurrence_count, total_deduction, passed, evidence_json` | grader 扣分输出 |
| `eval_case_results` | `run_id, case_id, score_mean, correctness_rate, aggregate_json, passed` | 多 Trial 稳定性聚合 |
| `eval_reviews` | `trial_id, reviewer_id, rubric_version, deductions_json` | 人工扣分复核 |
| `eval_bad_cases` | `case_id, source_run_id, status, owner, resolution` | Bad Case 闭环 |

约束：

- `eval_trace_events(trial_id, seq)` 唯一；
- 所有表必须包含创建/更新时间；Trace 为 append-only；
- suite definition、run config、grader config 必须保存不可变快照和 hash；
- 删除 Agent 或用户时遵循现有租户级联策略；
- 大型 artifact 放 workspace/object store，数据库只保存引用和 hash。

## 9. HTTP API 合同

建议沿用 FastClaw 的 Agent 授权风格：读取使用 `requireAgentReadable`，变更使用 `requireWritable`。

| Method | Path | 用途 |
|---|---|---|
| `POST` | `/api/agents/{id}/eval/suites` | 创建套件版本 |
| `GET` | `/api/agents/{id}/eval/suites` | 列出套件 |
| `GET` | `/api/agents/{id}/eval/suites/{suiteId}` | 获取定义 |
| `POST` | `/api/agents/{id}/eval/runs` | 启动运行 |
| `GET` | `/api/agents/{id}/eval/runs/{runId}` | 状态和摘要 |
| `POST` | `/api/agents/{id}/eval/runs/{runId}/cancel` | 取消运行 |
| `GET` | `/api/agents/{id}/eval/runs/{runId}/cases` | 用例聚合结果 |
| `GET` | `/api/agents/{id}/eval/trials/{trialId}` | Trial、grader 和 Trace |
| `POST` | `/api/agents/{id}/eval/trials/{trialId}/reviews` | 提交人工评分 |
| `POST` | `/api/agents/{id}/eval/runs/{runId}/compare` | 与 baseline 比较 |
| `GET` | `/api/agents/{id}/eval/runs/{runId}/report` | JSON/HTML 报告 |

启动请求：

```json
{
  "suite_id": "process-analysis-v1",
  "agent_revision": "sha256:...",
  "trials": 3,
  "baseline_run_id": "run_previous",
  "mode": "replay",
  "overrides": {
    "model_ref": "provider/model",
    "timeout_ms": 120000
  }
}
```

`POST /runs` SHOULD 返回 `202 Accepted` 和稳定 `run_id`。客户端轮询或 SSE 订阅状态，不得让 HTTP 请求阻塞整个套件执行。

## 10. 报告与回归门禁

### 10.1 报告必须包含

- Agent、Skill、Prompt、模型、工具、套件、grader 的版本/hash；
- 套件类型（capability/regression）以及 Agent harness 和模型的独立版本；
- 用例数、Trial 数、通过/失败/无效数；
- 每个 Trial 的 100 分起点、结果/过程/效率/其他扣分明细、最终分和门禁状态；
- 五个诊断维度的扣分汇总，不生成五维加权分；
- pass@k、pass^k、pass rate、均分、标准差；
- Token、模型调用、工具调用、延迟、重试和估算成本；
- 与 baseline 的绝对差、相对差和新增/修复/复发 Bad Case；
- capability 饱和度、回归 Case 健康度和类别分布；
- Outcome 与 Agent 最终声明不一致的数量；
- 可下钻 Trace 和 grader 证据；
- 机器可读 JSON 与便于评审的 HTML。

### 10.2 CI 判定

默认策略必须可配置，建议：

```yaml
release_gate:
  scoring_mode: deduction
  require_all_p0_cases: true
  max_new_p0_failures: 0
  require_correct_outcome: true
  regression_correctness_rate_min: 1.0
  min_trial_score: 80
  min_case_score: 80
  max_case_score_drop: 0
  max_run_score_drop: 0
```

回归评测的默认触发条件为：PR 合入、Agent/Skill/Prompt 变更、模型升级和定期巡检。Capability 评测可按研发需要或模型候选触发。

CI 失败必须指出具体 Case、Trial、扣分规则、扣分值和 evidence，不能只返回“低于 80 分”。结果错误必须明确显示 `result_penalty=-100`。基础设施或 grader 故障应标记为 `invalid/error`，不得伪装成产品回归。

### 10.3 Bad Case 生命周期

```text
open -> triaged -> fixing -> verification -> closed
  ^                                  |
  +-------------- reopened <---------+
```

每个 Bad Case 必须记录根因分类（Prompt/Skill/工具/模型/数据/基础设施/grader）、负责人、关联修复、复测 run 和是否已转为永久回归用例。

### 10.4 自动评测之外的质量闭环

自动 eval 不是完整质量系统。生产 Agent 上线后 SHOULD 组合以下信号：

| 方法 | 主要价值 | 局限 |
|---|---|---|
| 自动 eval | 提交前可重复、无用户影响、适合大规模回归 | 可能偏离真实流量，需要持续维护 |
| 生产监控 | 发现真实分布、错误和漂移 | 反应式，缺少天然 Ground Truth |
| A/B 测试 | 验证真实用户结果 | 需要流量和时间，只能测试已发布版本 |
| 用户反馈 | 发现设计者未预料的问题 | 稀疏、自选择、通常不解释原因 |
| 人工 Trace 阅读 | 建立失败模式直觉、校准 grader | 慢、覆盖有限、存在评审疲劳 |
| 系统化人工研究 | 主观/高风险任务的金标准 | 成本高，需处理评审者分歧 |

建议节奏：每次变更运行 regression；研发阶段运行 capability；上线后持续生产监控；每周抽样阅读 Trace；重大变更做 A/B；定期用专家样本重新校准 LLM grader。生产失败在脱敏和人工确认后 SHOULD 转成离线 Case。

## 11. 安全、隐私与可靠性

- Trace 写入前必须按字段策略脱敏；API key、密码、Cookie 和 Authorization header 永不落盘；
- PII 默认仅保存 hash 或脱敏值；原文保留需显式策略和 TTL；
- 工具 fixture 必须可审查，禁止包含生产密钥；
- LLM Judge 输入遵循最小化原则，并视为可能外发的数据；
- 评测 API 必须执行租户和 Agent 权限检查；禁止只凭 run ID 读取；
- 运行、Trial 和 grader 必须有资源上限，防止无限循环和费用失控；
- 用户可取消、可设置预算，超预算结论为 `budget_exceeded`；
- Trace/event payload 必须设大小上限，大输出存 artifact 并使用 hash 引用。

## 12. 实施顺序

### Phase 0：Trace 基础

- 定义 schema、数据库迁移和 append-only sink；
- 接入 Agent hooks；
- 能从一次隔离会话导出完整 JSON Trace；
- 支持逐行 JSONL 导出，事件顺序稳定，单行损坏可定位；
- 单独捕获并持久化最终 Outcome；
- 加入脱敏、大小限制、租户授权。

### Phase 1：离线 Runner 与确定性评分

- YAML/JSON 套件加载和 schema 校验；
- 多 Trial 调度、超时、取消、fixture replay；
- 确定性 graders；
- Reference Solution 验证、Outcome state check、构建/运行冒烟和仓库清洁检查；
- Case/Run 聚合和 JSON 报告；
- CLI 或内部 API 可在 CI 中运行。

### Phase 2：Rubric 与人工校准

- 只读、结构化 LLM Judge；输出受 JSON Schema 约束并支持 `unknown`；
- rubric 版本管理；
- Ground Truth 校准集和一致率报告；
- 人工复核 API/界面。

### Phase 3：产品化闭环

- HTML dashboard、Trace 下钻、run compare；
- Bad Case 管理和一键转回归用例；
- CI 门禁、趋势、成本和稳定性看板；
- 定时评测与告警。

## 13. Definition of Done

首个可用版本只有在以下条件全部满足时才算完成：

- [ ] 套件定义有 JSON Schema，非法字段、重复 ID 和无确定性期望的 P0 Case 会被拒绝；
- [ ] 每个 P0 Case 有通过全部 grader 的 Reference Solution，或记录了经批准的缺失原因；
- [ ] 同一 Case 可运行 N 次，每个 Trial 使用独立 session/workspace；
- [ ] 文件、数据库、缓存、记忆和 Git 状态会在 Trial 前重置，相关基础设施失败不会计入能力分；
- [ ] Trace 覆盖模型、工具、输出、错误、Token、耗时和重试，事件顺序可验证；
- [ ] Outcome 独立于 Trace/最终回答存储，能识别“口头成功、实际未完成”；
- [ ] Trace 中的密钥和配置的 PII 样本被脱敏；
- [ ] 至少实现第 7.2 节列出的确定性 graders；
- [ ] Grader error 与 Agent failure 被严格区分；
- [ ] 正确但路径不同的解不会因非契约性工具顺序而失败；
- [ ] LLM grader 支持 `unknown`，按维度评分，并通过人工校准阈值后才可做门禁；
- [ ] 评分只采用负分制：100 分起步，结果错误或安全红线扣 100，过程违规和效率超限按配置扣分；
- [ ] Outcome 错误时 Trial 固定为 0 分并失败，不再计算可通过的过程分；
- [ ] 每个扣分项均有 rule ID、次数、单次/总扣分和证据；
- [ ] Regression 的 correctness rate 必须为 100%，Case/Run 分数不得低于 baseline；
- [ ] 报告包含套件类型、扣分明细、多 Trial 稳定性、成本、baseline 差异、Outcome 和证据链接；
- [ ] 取消、总超时、工具超时、模型错误、Worker 重启均有自动测试；
- [ ] 跨用户或跨 Agent 读取 eval 数据会返回拒绝；
- [ ] 至少一个历史 Bad Case 完成“发现 -> 修复 -> 转回归 -> CI 验证”闭环；
- [ ] 核心聚合算法、状态机、权限和迁移有单元/集成测试，并通过 `go test ./...`。

## 14. 必测验收场景

1. Skill 选择：显式、隐式、上下文三类正例均正确触发，负向控制不误触发。
2. 路径弹性：使用非预期但合法工具路径得到正确 Outcome 时通过；安全关键顺序错误时失败。
3. 参数错误：工具收到非法参数时，grader 给出具体 event/field 证据。
4. 工具超时：Agent 可恢复或明确失败，不得静默成功。
5. Prompt injection：Agent 不泄露系统提示、不越权调用工具。
6. PII：Trace 和报告不出现原始敏感值。
7. 多 Trial：2/3 Outcome 正确时 regression 必须失败；3/3 正确后再按平均扣分判断是否达到 80。
8. Grader 故障：Judge 超时使运行无效或降级，不计为 Agent 失败。
9. Baseline 回归：平均分相近但新增任一 Outcome 错误时 CI 仍失败；结果均正确但分数下降时也失败。
10. 幂等恢复：Worker 在 Trial 中断后恢复，不重复计分或产生双份事件。
11. Reference Solution：套件发布前可通过所有 grader；已知错误解无法投机通过。
12. 环境泄漏：第二个 Trial 无法读取第一个 Trial 的文件、缓存、记忆或 Git 历史。
13. Capability 饱和：达到配置阈值后报告给出增加难例或毕业到 regression 的提示。
14. 负分计算：结果错误为 0 分；结果正确且 1 个过程违规、超时 1 个单位时为 80 分。

## 15. 原文映射与工程化补充

| 本文内容 | PDF 页码 | 说明 |
|---|---:|---|
| 背景、Trace、三层评分 | 1-6 | 原方案核心方法论 |
| 五维指标与优先级 | 6-10 | 归一化为统一维度表 |
| Skill 测试集、正负/边界/异常样例 | 10-18 | 转为 Case 分类和 YAML 模型 |
| Trace 与自动化工程 | 18-21 | 补齐事件 schema、状态机和隔离要求 |
| TPerf AI 分析 Agent 案例 | 21-36 | 提炼多 Trial、阈值、评分、Knot/报告闭环 |
| 参考资料与术语 | 36-38 | 用于术语统一 |
| API、数据库、CI、权限、DoD | 本文新增 | 原文未给固定合同，按 FastClaw 现有结构工程化补齐 |
| Outcome、harness、两类套件、grader 审计 | Anthropic 引用 | 根据可验证官方文章补充 |
| Skill 四类触发、定向 Prompt 集、JSONL、只读结构化评分 | OpenAI 引用 | 根据官方 `eval-skills` 文章补充 |

当本文中的“默认建议”与实际业务约束冲突时，修改套件配置并记录版本；不得在实现中静默改变评分语义。

