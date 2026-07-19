package ai.openagent.agent.eval;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 预期结果定义
 */
@Data
public class EvalExpected {

    /**
     * 工具调用预期
     */
    private ToolExpected tools;

    /**
     * 输出内容预期
     */
    private OutputExpected output;

    /**
     * 结果状态预期（文件等）
     */
    private OutcomeExpected outcome;

    /**
     * 约束条件
     */
    private ConstraintExpected constraints;

    /**
     * 最大值限制
     */
    private MaxLimits max;

    /**
     * 工具调用预期
     */
    @Data
    public static class ToolExpected {
        /**
         * 必须调用的工具列表
         */
        private List<String> required = Collections.emptyList();

        /**
         * 禁止调用的工具列表
         */
        private List<String> forbidden = Collections.emptyList();

        /**
         * 是否要求按顺序调用
         */
        private boolean ordered = false;

        /**
         * 工具重复调用最大次数
         */
        private Integer toolRepetitionMax;
    }

    /**
     * 输出内容预期
     */
    @Data
    public static class OutputExpected {
        /**
         * 输出必须包含的关键词
         */
        private List<String> mustContain = Collections.emptyList();

        /**
         * 输出禁止包含的关键词
         */
        private List<String> forbidden = Collections.emptyList();

        /**
         * 是否允许语义相近的表达（而非精确匹配）
         */
        private boolean semanticMatch = true;
    }

    /**
     * 结果状态预期
     */
    @Data
    public static class OutcomeExpected {
        /**
         * 预期存在的文件路径
         */
        private String fileExists;

        /**
         * 文件内容应包含的字符串
         */
        private String fileContentContains;

        /**
         * 预期目录存在
         */
        private String dirExists;
    }

    /**
     * 约束条件
     */
    @Data
    public static class ConstraintExpected {
        /**
         * 最大迭代次数
         */
        private Integer maxIterations;
    }

    /**
     * 最大值限制
     */
    @Data
    public static class MaxLimits {
        /**
         * 最大工具调用次数
         */
        private Integer toolCalls;

        /**
         * 最大延迟（毫秒）
         */
        private Long latencyMs;

        /**
         * 最大总 token 数
         */
        private Integer totalTokens;
    }
}
