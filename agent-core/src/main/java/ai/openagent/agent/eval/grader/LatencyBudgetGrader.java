package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.MaxLimits;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 延迟预算评分器
 * - 检查运行时间是否超过 max.latency_ms
 */
public class LatencyBudgetGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        MaxLimits max = testCase.getExpected().getMax();
        if (max == null || max.getLatencyMs() == null) {
            return GraderResult.success();
        }

        long maxLatency = max.getLatencyMs();
        long actualLatency = calculateLatency(context);

        if (actualLatency > maxLatency) {
            int deduction = testCase.getScoring().getProcessViolationPenalty();
            String reason = String.format("延迟超限: %dms > %dms", actualLatency, maxLatency);
            return GraderResult.failed(deduction, reason,
                    List.of("实际延迟: " + actualLatency + "ms, 上限: " + maxLatency + "ms"));
        }

        return GraderResult.success(List.of("延迟: " + actualLatency + "ms, 上限: " + maxLatency + "ms"));
    }

    /**
     * 计算延迟（毫秒）
     */
    private long calculateLatency(EvalContext context) {
        // 优先使用预计算的 latencyMs
        if (context.getLatencyMs() != null) {
            return context.getLatencyMs();
        }

        // 从起止时间计算
        Instant start = context.getStartTime();
        Instant end = context.getEndTime();
        if (start != null && end != null) {
            return Duration.between(start, end).toMillis();
        }

        // 从 AgentRunResult 计算（如果有时间戳）
        if (context.getRunResult() != null) {
            // 这里假设 AgentRunResult 有方法获取时间戳
            // 如果不可用，返回 0
        }

        return 0;
    }
}
