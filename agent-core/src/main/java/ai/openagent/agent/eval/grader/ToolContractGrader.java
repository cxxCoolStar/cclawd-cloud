package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import ai.openagent.agent.eval.EvalExpected.ToolExpected;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具契约评分器
 * - 检查 required 工具是否都被调用
 * - 检查 forbidden 工具是否未被调用
 * - 检查调用次数是否超过 max.tool_calls
 */
public class ToolContractGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        List<String> evidence = new ArrayList<>();
        int totalDeduction = 0;
        StringBuilder reasonBuilder = new StringBuilder();

        ToolExpected tools = testCase.getExpected().getTools();
        if (tools == null) {
            return GraderResult.success();
        }

        // 获取实际调用的工具列表
        List<String> actualTools = context.getToolCalls().stream()
                .map(EvalContext.ToolCall::getToolName)
                .collect(Collectors.toList());

        evidence.add("实际调用工具: " + actualTools);

        // 检查 required 工具
        List<String> required = tools.getRequired();
        if (required != null && !required.isEmpty()) {
            Set<String> actualToolSet = new HashSet<>(actualTools);
            List<String> missing = required.stream()
                    .filter(r -> !actualToolSet.contains(r))
                    .collect(Collectors.toList());
            if (!missing.isEmpty()) {
                totalDeduction += testCase.getScoring().getProcessViolationPenalty();
                reasonBuilder.append("未调用必需工具: ").append(missing).append("; ");
                evidence.add("缺失工具: " + missing);
            }
        }

        // 检查 forbidden 工具
        List<String> forbidden = tools.getForbidden();
        if (forbidden != null && !forbidden.isEmpty()) {
            List<String> violated = actualTools.stream()
                    .filter(forbidden::contains)
                    .collect(Collectors.toList());
            if (!violated.isEmpty()) {
                totalDeduction += testCase.getScoring().getProcessViolationPenalty() * violated.size();
                reasonBuilder.append("调用了禁止工具: ").append(violated).append("; ");
                evidence.add("违规工具: " + violated);
            }
        }

        // 检查调用顺序（如果 ordered=true）
        // 允许工具重复调用，但要求相对顺序正确
        if (tools.isOrdered() && required != null && required.size() > 1) {
            List<String> requiredInOrder = actualTools.stream()
                    .filter(required::contains)
                    .collect(Collectors.toList());
            // 检查相对顺序：required 中的每个工具必须在 required[i+1] 之前出现
            boolean orderCorrect = true;
            for (int i = 0; i < required.size() - 1 && orderCorrect; i++) {
                String current = required.get(i);
                String next = required.get(i + 1);
                int currentIndex = requiredInOrder.indexOf(current);
                int nextIndex = requiredInOrder.indexOf(next);
                // next 必须在 current 的某次出现之后
                if (currentIndex == -1 || nextIndex == -1) {
                    orderCorrect = false; // 缺少必需工具
                } else {
                    // 找到 current 的最后一次出现位置
                    int lastCurrentIndex = -1;
                    for (int j = 0; j < requiredInOrder.size(); j++) {
                        if (requiredInOrder.get(j).equals(current)) {
                            lastCurrentIndex = j;
                        }
                    }
                    // next 必须在 current 最后一次出现之后
                    if (nextIndex <= lastCurrentIndex) {
                        orderCorrect = false;
                    }
                }
            }
            if (!orderCorrect) {
                totalDeduction += testCase.getScoring().getProcessViolationPenalty();
                reasonBuilder.append("工具调用顺序错误，期望: ").append(required)
                        .append(", 实际: ").append(requiredInOrder).append("; ");
                evidence.add("顺序错误，实际: " + requiredInOrder);
            }
        }

        // 检查工具重复调用次数
        Integer repetitionMax = tools.getToolRepetitionMax();
        if (repetitionMax != null && repetitionMax > 0) {
            Map<String, Long> toolCounts = actualTools.stream()
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
            List<String> violations = toolCounts.entrySet().stream()
                    .filter(e -> e.getValue() > repetitionMax)
                    .map(e -> e.getKey() + "(" + e.getValue() + "次)")
                    .collect(Collectors.toList());
            if (!violations.isEmpty()) {
                totalDeduction += testCase.getScoring().getProcessViolationPenalty() * violations.size();
                reasonBuilder.append("工具重复调用超限: ").append(violations).append("; ");
                evidence.add("重复调用超限: " + violations);
            }
        }

        // 检查最大工具调用次数
        if (testCase.getExpected().getMax() != null 
                && testCase.getExpected().getMax().getToolCalls() != null) {
            int maxCalls = testCase.getExpected().getMax().getToolCalls();
            if (actualTools.size() > maxCalls) {
                int extra = actualTools.size() - maxCalls;
                int penalty = testCase.getScoring().getEfficiencyPenaltyPerExtraCall() > 0
                        ? testCase.getScoring().getEfficiencyPenaltyPerExtraCall() * extra
                        : testCase.getScoring().getProcessViolationPenalty();
                totalDeduction += penalty;
                reasonBuilder.append("工具调用次数超限: ").append(actualTools.size())
                        .append(" > ").append(maxCalls).append("; ");
                evidence.add("调用次数: " + actualTools.size() + ", 上限: " + maxCalls);
            }
        }

        if (totalDeduction == 0) {
            return GraderResult.success(evidence);
        }
        return GraderResult.failed(totalDeduction, reasonBuilder.toString().trim(), evidence);
    }
}
