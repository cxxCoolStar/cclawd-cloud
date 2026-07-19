package ai.openagent.agent.eval;

import java.util.Collections;
import java.util.List;

/**
 * 评分结果
 *
 * @param passed   是否通过
 * @param deduction 扣分（正数）
 * @param reason   未通过原因
 * @param evidence 证据列表
 */
public record GraderResult(
        boolean passed,
        int deduction,
        String reason,
        List<String> evidence) {

    /**
     * 通过的默认结果
     */
    public static GraderResult success() {
        return new GraderResult(true, 0, null, Collections.emptyList());
    }

    /**
     * 通过的带证据结果
     */
    public static GraderResult success(List<String> evidence) {
        return new GraderResult(true, 0, null, evidence);
    }

    /**
     * 失败的结果
     */
    public static GraderResult failed(int deduction, String reason) {
        return new GraderResult(false, deduction, reason, Collections.emptyList());
    }

    /**
     * 失败的结果（带证据）
     */
    public static GraderResult failed(int deduction, String reason, List<String> evidence) {
        return new GraderResult(false, deduction, reason, evidence);
    }
}
