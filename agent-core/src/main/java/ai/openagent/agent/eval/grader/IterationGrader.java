package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.ConstraintExpected;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.util.List;

/**
 * 迭代次数评分器
 * - 检查 ReAct 迭代轮数是否超过 constraints.max_iterations（循环保护类用例）
 */
public class IterationGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        ConstraintExpected constraints = testCase.getExpected().getConstraints();
        if (constraints == null || constraints.getMaxIterations() == null) {
            return GraderResult.success();
        }

        int maxIterations = constraints.getMaxIterations();
        if (context.getRunResult() == null) {
            return GraderResult.failed(
                    testCase.getScoring().getProcessViolationPenalty(),
                    "无法获取运行结果，迭代次数未知",
                    List.of("runResult 为 null"));
        }

        int actualIterations = context.getRunResult().toolIterations();
        if (actualIterations > maxIterations) {
            int deduction = testCase.getScoring().getProcessViolationPenalty();
            String reason = String.format("迭代次数超限: %d > %d", actualIterations, maxIterations);
            return GraderResult.failed(deduction, reason,
                    List.of("实际迭代: " + actualIterations + ", 上限: " + maxIterations));
        }

        return GraderResult.success(
                List.of("迭代次数: " + actualIterations + ", 上限: " + maxIterations));
    }
}
