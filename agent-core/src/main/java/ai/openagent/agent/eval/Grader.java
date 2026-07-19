package ai.openagent.agent.eval;

/**
 * 评分器接口
 */
public interface Grader {

    /**
     * 对测试用例进行评分
     *
     * @param testCase 测试用例定义
     * @param context  评估上下文（包含实际运行结果）
     * @return 评分结果
     */
    GraderResult grade(EvalCase testCase, EvalContext context);
}
