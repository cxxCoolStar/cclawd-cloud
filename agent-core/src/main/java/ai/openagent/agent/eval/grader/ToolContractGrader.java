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
        // 子序列匹配：required 依次在实际序列中找到即可，允许穿插重复调用
        // （如 read → write → read 回读验证，required=[read, write] 应判通过）
        if (tools.isOrdered() && required != null && required.size() > 1) {
            List<String> requiredInOrder = actualTools.stream()
                    .filter(required::contains)
                    .collect(Collectors.toList());
            int searchFrom = 0;
            boolean orderCorrect = true;
            for (String tool : required) {
                int found = -1;
                for (int j = searchFrom; j < actualTools.size(); j++) {
                    if (actualTools.get(j).equals(tool)) {
                        found = j;
                        break;
                    }
                }
                if (found == -1) {
                    orderCorrect = false;
                    break;
                }
                searchFrom = found + 1;
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
