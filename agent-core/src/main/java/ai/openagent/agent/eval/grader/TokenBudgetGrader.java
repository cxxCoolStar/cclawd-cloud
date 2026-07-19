package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.MaxLimits;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.util.List;

/**
 * Token 预算评分器
 * - 检查 token 用量是否超过约束
 */
public class TokenBudgetGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        MaxLimits max = testCase.getExpected().getMax();
        if (max == null) {
            return GraderResult.success();
        }

        EvalContext.TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage == null) {
            tokenUsage = EvalContext.TokenUsage.empty();
        }

        int totalTokens = tokenUsage.getTotalTokens();
        int inputTokens = tokenUsage.getInputTokens();
        int outputTokens = tokenUsage.getOutputTokens();

        // 检查 total_tokens 限制
        if (max.getTotalTokens() != null && totalTokens > max.getTotalTokens()) {
            int deduction = testCase.getScoring().getProcessViolationPenalty();
            String reason = String.format("Token 用量超限: %d > %d", totalTokens, max.getTotalTokens());
            return GraderResult.failed(deduction, reason, List.of(
                    "实际用量: " + totalTokens + ", 上限: " + max.getTotalTokens(),
                    "input: " + inputTokens + ", output: " + outputTokens));
        }

        return GraderResult.success(List.of(
                "Token 用量: " + totalTokens + " (input: " + inputTokens + ", output: " + outputTokens + ")"));
    }
}
