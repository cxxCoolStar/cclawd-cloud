package ai.openagent.agent.eval.grader;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.ConstraintExpected;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.util.List;
import java.util.Objects;

/**
 * 迭代次数与循环保护评分器。
 */
public class IterationGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        ConstraintExpected constraints = testCase.getExpected().getConstraints();
        if (constraints == null) {
            return GraderResult.success();
        }
        if (context.getRunResult() == null) {
            return GraderResult.failed(
                    testCase.getScoring().getProcessViolationPenalty(),
                    "无法获取运行结果，迭代次数未知",
                    List.of("runResult 为 null"));
        }

        int actualIterations = context.getRunResult().toolIterations();
        Integer maxIterations = constraints.getMaxIterations();
        if (maxIterations != null && actualIterations > maxIterations) {
            return GraderResult.failed(
                    testCase.getScoring().getProcessViolationPenalty(),
                    String.format("迭代次数超限: %d > %d", actualIterations, maxIterations),
                    List.of("实际迭代: " + actualIterations + ", 上限: " + maxIterations));
        }

        if (constraints.isLoopProtectionRequired()) {
            int maxConsecutive = maxConsecutiveIdenticalCalls(context.getToolCalls());
            boolean limitReached = context.getRunResult().status() == AgentRunStatus.LIMIT_REACHED;
            if (!limitReached || maxConsecutive < 3) {
                return GraderResult.failed(
                        testCase.getScoring().getResultIncorrectPenalty(),
                        "未观察到循环保护触发",
                        List.of("运行状态: " + context.getRunResult().status()
                                + ", 连续相同工具请求最大次数: " + maxConsecutive));
            }
            return GraderResult.success(List.of(
                    "循环保护已触发，连续相同工具请求: " + maxConsecutive,
                    "迭代次数: " + actualIterations
                            + (maxIterations == null ? "" : ", 上限: " + maxIterations)));
        }

        return GraderResult.success(maxIterations == null
                ? List.of()
                : List.of("迭代次数: " + actualIterations + ", 上限: " + maxIterations));
    }

    private int maxConsecutiveIdenticalCalls(List<EvalContext.ToolCall> calls) {
        String previousTool = null;
        String previousArguments = null;
        int consecutive = 0;
        int maximum = 0;
        for (EvalContext.ToolCall call : calls) {
            if (Objects.equals(previousTool, call.getToolName())
                    && Objects.equals(previousArguments, call.getArguments())) {
                consecutive++;
            } else {
                previousTool = call.getToolName();
                previousArguments = call.getArguments();
                consecutive = 1;
            }
            maximum = Math.max(maximum, consecutive);
        }
        return maximum;
    }
}