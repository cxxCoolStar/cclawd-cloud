# OpenAgent Java Eval 测试指南

> 版本：v1.0  
> 日期：2026-07-19  
> 关联文档：[EVALUATION_PLAN.md](./EVALUATION_PLAN.md)

---

## 1. 快速开始（5 分钟上手）

### 1.1 前置条件

1. **项目已编译**
   ```bash
   ./mvnw clean compile -DskipTests
   ```

2. **LLM 配置已就绪**  
   Eval 测试需要真实的 LLM 调用，请确保以下配置已写入数据库：
   - `infra-ai` 模块的模型配置（如 kimi-k2.5）
   - API Key 已配置

3. **Eval Profile 配置**  
   `bootstrap/src/main/resources/application-eval.yml` 已存在，使用独立的 H2 数据库隔离测试数据。

### 1.2 运行单个用例

```bash
# 运行默认用例（file-read-basic）
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runSingleCase -Dspring.profiles.active=eval

# 运行指定用例
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runSingleCase -DcaseId=exec-basic -Dspring.profiles.active=eval
```

### 1.3 运行全部用例

```bash
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runAllCases -Dspring.profiles.active=eval
```

### 1.4 查看结果

测试完成后，报告会写入：
```
bootstrap/target/eval-report.json
```

控制台也会输出摘要：
```
Eval summary: total=17, passed=15, passRate=88.2%, meanScore=85.3
```

---

## 2. 用例文件格式

### 2.1 文件位置

所有用例存放在：`eval/cases/*.yaml`

### 2.2 完整示例

```yaml
# eval/cases/file-read-basic.yaml
id: file-read-basic                    # 唯一标识
name: 读取文件并总结内容                # 显示名称
category: core_logic                   # 分类：core_logic / security / efficiency
priority: P0                           # 优先级：P0/P1/P2

# 用户输入（当前只支持单轮）
input: "请读取 workspace/project/README.md 文件，并总结它的主要内容"

# 预期结果定义
expected:
  # 工具调用预期
  tools:
    required: ["read_file"]            # 必须调用的工具
    forbidden: ["write_file", "edit_file", "exec"]  # 禁止调用的工具
    ordered: false                     # 是否要求按顺序调用
    tool_repetition_max: 3             # 单个工具最大重复次数
  
  # 输出内容预期
  output:
    must_contain: ["README", "项目", "介绍"]   # 输出必须包含的关键词
    forbidden: ["错误", "无法读取", "找不到"]  # 输出不能包含的关键词
    semantic_match: true               # 是否启用语义匹配（预留）
  
  # 执行结果预期
  outcome:
    file_exists: "output.txt"          # 期望存在的文件
    file_content_contains: "summary"   # 文件内容期望
    dir_exists: "reports"              # 期望存在的目录
  
  # 约束条件
  constraints:
    max_iterations: 5                  # 最大迭代次数
  
  # 性能预算
  max:
    tool_calls: 2                      # 最大工具调用次数
    latency_ms: 8000                   # 最大延迟（毫秒）
    total_tokens: 5000                 # 最大 Token 数

# 评分配置
scoring:
  mode: deduction                      # 评分模式：deduction（扣分制）
  max_score: 100                       # 满分
  pass_threshold: 80                   # 通过阈值
  result_incorrect_penalty: 100        # 结果错误扣分
  process_violation_penalty: 10        # 过程违规扣分
  efficiency_bonus: false              # 是否启用效率奖励
  efficiency_penalty_per_extra_call: 10  # 额外调用惩罚

# 夹具：测试前置条件
fixtures:
  # 预置文件（已支持）
  files:
    - path: "project/README.md"
      content: |
        # OpenAgent 项目
        这是一个基于 ReAct 架构的 AI Agent 实现。
    - path: "project/main.py"
      content: "print('hello')"
  
  # 预置记忆（已支持）
  memory:
    - "项目使用 Java 17 和 Spring Boot 3"
    - "数据库是 PostgreSQL"
  
  # 预置技能（预留，暂未实现）
  skills:
    - name: "代码审查"
      trigger: "review"

# 备注
notes: "测试最基本的文件读取能力，不应触发编辑或执行工具"
```

### 2.3 最小用例

```yaml
id: simple-test
name: 简单测试
input: "你好"
expected:
  output:
    must_contain: ["你好"]
scoring:
  pass_threshold: 80
```

---

## 3. Fixtures（前置条件）

Fixtures 用于在测试运行前准备环境，支持三种类型：

### 3.1 文件 Fixtures（`files`）

在 workspace 目录下创建预置文件：

```yaml
fixtures:
  files:
    - path: "project/README.md"        # 相对于 workspace 的路径
      content: "# 项目介绍\n这是内容"
    - path: "data/users.csv"           # 会自动创建父目录
      content: |
        name,age
        Alice,25
        Bob,30
```

**注意事项：**
- `path` 可以带或不带 `workspace/` 前缀，系统会自动处理
- 父目录会自动创建
- 文件内容支持多行字符串（使用 `|`）

### 3.2 记忆 Fixtures（`memory`）

向 Agent 的长期记忆中写入内容（用于测试 memory_search 等场景）：

```yaml
fixtures:
  memory:
    - "项目使用 Java 17 和 Spring Boot 3"
    - "数据库是 PostgreSQL"
    - "部署在 Kubernetes 集群"
```

**实现细节：**
- 内容会写入 `MEMORY.md` 文件
- 使用 `eval-user` 作为 userId 以隔离测试数据
- 需要 `MemoryService` 已启用（`memory.enabled=true`）
- 格式：自动转换为 `- 内容` 的列表格式

### 3.3 技能 Fixtures（`skills`）

预留字段，暂未实现。用于预注册技能。

---

## 4. 评分机制

### 4.1 评分器列表

| 评分器 | 检查内容 | 数据来源 |
|--------|----------|----------|
| `ToolContractGrader` | 必须/禁止工具、调用顺序、重复次数 | `tool_executions` 表 |
| `OutputContentGrader` | 输出包含/不包含关键词 | Agent 输出文本 |
| `OutcomeStateGrader` | 文件/目录终态 | workspace 目录 |
| `LatencyBudgetGrader` | 延迟是否超标 | 实际运行时间 |
| `TokenBudgetGrader` | Token 是否超标 | `agent_runs` 表 |

### 4.2 扣分规则

```
初始分数：100
- 结果错误（如运行失败）：-100 → 0 分
- 违反工具契约（如调用 forbidden 工具）：-10 分/项
- 输出缺少关键词：-10 分/项
- 超预算（延迟/Token）：-10 分/项
- 运行状态异常（FAILED/TIMED_OUT/INTERRUPTED）：直接 0 分

通过条件：score >= pass_threshold（默认 80）
```

---

## 5. 调试技巧

### 5.1 查看详细日志

```bash
# 开启 DEBUG 日志
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runSingleCase \
  -Dspring.profiles.active=eval \
  -Dlogging.level.ai.openagent.bootstrap.eval=DEBUG
```

### 5.2 查看 Trace

每个用例运行时会生成 `runId`（如 `eval-a1b2c3d4`），可通过 API 查看完整 trace：

```bash
# 启动应用后调用
curl -H "Authorization: Bearer ${TOKEN}" \
  http://localhost:8080/api/runs/eval-a1b2c3d4/trace
```

Trace 包含：
- 完整的事件序列
- 每次模型调用的 Token 用量
- 每次工具调用的参数和结果

### 5.3 失败用例自动导出 Trace

失败用例会自动在日志中输出关键信息：
```
Case failed: id=file-read-basic, score=60
Deductions:
  - ToolContractGrader: -20, "Forbidden tool called: write_file"
  - OutputContentGrader: -20, "Missing keywords: [项目]"
```

---

## 6. 添加新用例

### 6.1 创建 YAML 文件

在 `eval/cases/` 下新建 `.yaml` 文件：

```bash
# 示例：测试错误处理
cat > eval/cases/error-handling.yaml << 'EOF'
id: error-handling
name: 错误处理测试
input: "请读取一个不存在的文件 /nonexistent.txt"
expected:
  output:
    must_contain: ["不存在", "无法"]
    forbidden: ["成功", "已读取"]
  tools:
    required: ["read_file"]
  max:
    tool_calls: 1
scoring:
  pass_threshold: 80
notes: "测试 Agent 对文件不存在场景的处理"
EOF
```

### 6.2 验证用例格式

```bash
# 运行该用例验证
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runSingleCase -DcaseId=error-handling
```

### 6.3 用例命名规范

| 前缀 | 用途 | 示例 |
|------|------|------|
| `file-*` | 文件操作 | `file-read-basic`, `file-write-large` |
| `dir-*` | 目录操作 | `dir-list-basic`, `dir-list-nested` |
| `exec-*` | 命令执行 | `exec-basic`, `exec-dangerous-blocked` |
| `web-*` | 网络工具 | `web-search-basic`, `web-fetch-error` |
| `memory-*` | 记忆功能 | `memory-search-basic`, `memory-context` |
| `security-*` | 安全测试 | `security-prompt-injection`, `security-path-traversal` |
| `efficiency-*` | 效率测试 | `efficiency-budget`, `efficiency-loop-protection` |

---

## 7. CI 集成

### 7.1 手动触发

Eval 测试默认不随常规测试运行，需显式触发：

```bash
# 本地触发
./mvnw test -pl bootstrap -Dtest=EvalRunnerTest#runAllCases -Dspring.profiles.active=eval
```

### 7.2 GitHub Actions（预留）

```yaml
# .github/workflows/eval.yml
name: Eval Regression
on:
  schedule:
    - cron: '17 19 * * *'   # nightly
  workflow_dispatch:        # 手动触发
  pull_request:
    types: [labeled]        # 打 run-eval label 时触发
```

### 7.3 门禁配置（Phase 3）

```yaml
# 预留：回归套件配置
suite:
  name: 核心功能回归套件
  trials_per_case: 3        # 每个用例运行 3 次
  cases: [file-read-01, file-read-02, ...]
  
release_gate:
  require_correct_outcome: true
  min_case_score: 80
  min_pass_rate: 1.0
  max_new_failures: 0
```

---

## 8. 常见问题

### Q1: 运行报错 "No eval cases found"

**原因**：YAML 文件格式错误或路径不正确。  
**解决**：检查 `eval/cases/` 目录下是否有 `.yaml` 文件，且格式正确。

### Q2: Token 用量始终为 0

**原因**：`TokenUsageHook` 未正确挂载。  
**解决**：检查 `agent_runs` 表是否有 `input_tokens` 等列，确认 Phase 1 已完成。

### Q3: 工具调用记录为空

**原因**：事件监听未正确接收 `ToolCallRequested` / `ToolResultProduced` 事件。  
**解决**：检查 `AgentEventSink` 是否正确转发事件。

### Q4: 用例运行超时

**原因**：Agent 陷入死循环或 LLM 响应缓慢。  
**解决**：
- 检查 `max_iterations` 限制是否生效
- 查看 Trace 确认模型响应时间
- 调整 `AgentProperties.runTimeout()` 配置

---

## 8. 目录结构

```
eval/
├── cases/                          # 测试用例目录
│   ├── file-read-basic.yaml       # 基础文件读取
│   ├── file-write-basic.yaml      # 基础文件写入
│   ├── dir-list-basic.yaml        # 目录列表
│   ├── exec-basic.yaml            # 命令执行
│   ├── web-search-basic.yaml      # 网络搜索
│   ├── memory-search-basic.yaml   # 记忆搜索
│   ├── error-recovery-retry.yaml  # 错误恢复
│   ├── loop-protection.yaml       # 循环保护
│   ├── prompt-injection-attempt.yaml  # 注入攻击
│   ├── path-traversal-attempt.yaml    # 路径遍历
│   └── ...
└── baseline.json                  # 回归基线（Phase 3）

docs/eval/
├── EVALUATION_PLAN.md             # 实施方案
├── EVAL_TESTING_GUIDE.md          # 本文件：测试指南
└── ai-agent-evaluation-system-spec.md  # FastClaw 参考方案

bootstrap/src/test/java/.../eval/
├── EvalRunnerTest.java            # 测试运行器
└── EvalCaseLoader.java            # 用例加载器

agent-core/src/main/java/.../eval/
├── EvalCase.java                  # 用例数据模型
├── EvalContext.java               # 评估上下文
├── EvalReport.java                # 报告数据模型
├── Grader.java                    # 评分器接口
└── grader/
    ├── ToolContractGrader.java    # 工具契约评分
    ├── OutputContentGrader.java   # 输出内容评分
    ├── LatencyBudgetGrader.java   # 延迟预算评分
    ├── TokenBudgetGrader.java     # Token 预算评分
    └── OutcomeStateGrader.java    # 结果状态评分
```

---

## 9. 下一步计划

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | Trace 导出 + Token 落库 | ✅ 已完成 |
| Phase 2 | YAML 加载器 + 5 个评分器 + Runner | ✅ 已完成 |
| Phase 3 | 多 Trial + 回归门禁 + CI | 🔲 待开发 |
| Phase 3.5 | Record/Replay 确定性回归 | 🔲 按需 |
| Phase 4 | LLM-as-Judge Rubric | 🔲 按需 |

---

## 10. 参考

- [EVALUATION_PLAN.md](./EVALUATION_PLAN.md) - 完整实施方案
- [eval/cases/](../../eval/cases/) - 用例示例
- [EvalRunnerTest.java](../../bootstrap/src/test/java/ai/openagent/bootstrap/eval/EvalRunnerTest.java) - 运行器源码
