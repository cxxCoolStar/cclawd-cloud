package ai.openagent.agent.eval.grader;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected.OutputExpected;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 输出内容评分器
 * - 检查输出是否包含全部 must_contain 关键词
 * - 检查输出是否包含任一 must_contain_any 关键词
 * - 检查输出是否不包含 forbidden 关键词
 */
public class OutputContentGrader implements Grader {

    @Override
    public GraderResult grade(EvalCase testCase, EvalContext context) {
        List<String> evidence = new ArrayList<>();
        int totalDeduction = 0;
        StringBuilder reasonBuilder = new StringBuilder();

        OutputExpected output = testCase.getExpected().getOutput();
        if (output == null) {
            return GraderResult.success();
        }

        // 关键词归一化后为空串会使 contains 恒真，属于用例配置错误，直接判失败暴露问题
        String badKeyword = findEmptyNormalizedKeyword(output);
        if (badKeyword != null) {
            return GraderResult.failed(
                    testCase.getScoring().getResultIncorrectPenalty(),
                    "用例配置错误：关键词 \"" + badKeyword + "\" 归一化后为空（semantic_match 会剥掉标点/空格），断言恒真",
                    List.of("无效关键词: \"" + badKeyword + "\""));
        }

        String actualOutput = context.getOutput();
        if (actualOutput == null || actualOutput.isBlank()) {
            return GraderResult.failed(
                    testCase.getScoring().getResultIncorrectPenalty(),
                    "输出为空",
                    List.of("实际输出为空或 null"));
        }

        evidence.add("输出长度: " + actualOutput.length() + " 字符");

        // 检查 must_contain（全部命中）
        List<String> mustContain = output.getMustContain();
        if (mustContain != null && !mustContain.isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (String keyword : mustContain) {
                if (!containsKeyword(actualOutput, keyword, output.isSemanticMatch())) {
                    missing.add(keyword);
                }
            }
            if (!missing.isEmpty()) {
                totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                reasonBuilder.append("输出缺少必需内容: ").append(missing).append("; ");
                evidence.add("缺少关键词: " + missing);
            }
        }

        // 检查 must_contain_any（任一命中即可）
        List<String> mustContainAny = output.getMustContainAny();
        if (mustContainAny != null && !mustContainAny.isEmpty()) {
            boolean anyHit = mustContainAny.stream()
                    .anyMatch(k -> containsKeyword(actualOutput, k, output.isSemanticMatch()));
            if (!anyHit) {
                totalDeduction += testCase.getScoring().getResultIncorrectPenalty();
                reasonBuilder.append("输出未命中任一候选内容: ").append(mustContainAny).append("; ");
                evidence.add("未命中任一关键词: " + mustContainAny);
            } else {
                evidence.add("命中候选关键词（any）");
            }
        }

        // 检查 forbidden
        List<String> forbidden = output.getForbidden();
        if (forbidden != null && !forbidden.isEmpty()) {
            List<String> found = new ArrayList<>();
            for (String keyword : forbidden) {
                if (containsKeyword(actualOutput, keyword, output.isSemanticMatch())) {
                    found.add(keyword);
                }
            }
            if (!found.isEmpty()) {
                totalDeduction += testCase.getScoring().getProcessViolationPenalty() * found.size();
                reasonBuilder.append("输出包含禁止内容: ").append(found).append("; ");
                evidence.add("包含禁止关键词: " + found);
            }
        }

        if (totalDeduction == 0) {
            return GraderResult.success(evidence);
        }
        return GraderResult.failed(totalDeduction, reasonBuilder.toString().trim(), evidence);
    }

    /**
     * 找出归一化后为空的关键词（semantic_match 下 contains("") 恒真，属配置错误）；
     * semantic_match=false 时仅空白关键词无效
     */
    private String findEmptyNormalizedKeyword(OutputExpected output) {
        List<List<String>> groups = new ArrayList<>();
        groups.add(output.getMustContain());
        groups.add(output.getMustContainAny());
        groups.add(output.getForbidden());
        for (List<String> keywords : groups) {
            if (keywords == null) {
                continue;
            }
            for (String keyword : keywords) {
                String effective = output.isSemanticMatch() ? normalize(keyword) : keyword;
                if (effective == null || effective.isEmpty()) {
                    return keyword;
                }
            }
        }
        return null;
    }

    /**
     * 检查是否包含关键词
     * semanticMatch=true 时进行简单的语义匹配（忽略大小写和标点）
     */
    private boolean containsKeyword(String text, String keyword, boolean semanticMatch) {
        if (semanticMatch) {
            // 忽略大小写、空格和常见标点
            String normalizedText = normalize(text);
            String normalizedKeyword = normalize(keyword);
            return normalizedText.contains(normalizedKeyword);
        }
        return text.contains(keyword);
    }

    private String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("[\\s\\p{Punct}]+", "")
                .trim();
    }
}
