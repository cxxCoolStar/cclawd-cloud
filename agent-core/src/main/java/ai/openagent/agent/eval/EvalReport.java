package ai.openagent.agent.eval;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 评估报告
 *
 * @param runId        运行 ID
 * @param startTime    开始时间
 * @param endTime      结束时间
 * @param results      各用例结果
 * @param totalCases   总用例数
 * @param passedCases  通过数
 * @param passRate     通过率
 * @param meanScore    平均分数
 */
public record EvalReport(
        String runId,
        Instant startTime,
        Instant endTime,
        List<CaseResult> results,
        int totalCases,
        int passedCases,
        double passRate,
        double meanScore) {

    /**
     * 用例结果
     *
     * @param caseId      用例 ID
     * @param caseName    用例名称
     * @param category    分类
     * @param passed      是否通过
     * @param score       得分
     * @param deductions  扣分详情
     * @param evidence    证据
     * @param durationMs  执行时长
     * @param reasoning   模型的推理过程（reasoning_content）
     * @param output      模型输出内容
     */
    public record CaseResult(
            String caseId,
            String caseName,
            String category,
            boolean passed,
            int score,
            List<Deduction> deductions,
            List<String> evidence,
            long durationMs,
            String reasoning,
            String output) {

        public CaseResult {
            deductions = deductions == null ? Collections.emptyList() : deductions;
            evidence = evidence == null ? Collections.emptyList() : evidence;
        }

        /**
         * 兼容旧代码的构造器（不包含 reasoning 和 output）
         */
        public CaseResult(
                String caseId,
                String caseName,
                String category,
                boolean passed,
                int score,
                List<Deduction> deductions,
                List<String> evidence,
                long durationMs) {
            this(caseId, caseName, category, passed, score, deductions, evidence, durationMs, null, null);
        }
    }

    /**
     * 扣分项
     *
     * @param grader 评分器名称
     * @param points 扣分
     * @param reason 原因
     */
    public record Deduction(
            String grader,
            int points,
            String reason) {
    }

    /**
     * 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runId;
        private Instant startTime;
        private Instant endTime;
        private List<CaseResult> results;
        private int totalCases;
        private int passedCases;
        private double passRate;
        private double meanScore;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder results(List<CaseResult> results) {
            this.results = results;
            return this;
        }

        public Builder totalCases(int totalCases) {
            this.totalCases = totalCases;
            return this;
        }

        public Builder passedCases(int passedCases) {
            this.passedCases = passedCases;
            return this;
        }

        public Builder passRate(double passRate) {
            this.passRate = passRate;
            return this;
        }

        public Builder meanScore(double meanScore) {
            this.meanScore = meanScore;
            return this;
        }

        public EvalReport build() {
            return new EvalReport(runId, startTime, endTime, results,
                    totalCases, passedCases, passRate, meanScore);
        }
    }
}
