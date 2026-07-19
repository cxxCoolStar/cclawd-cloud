package ai.openagent.agent.eval;

import ai.openagent.agent.AgentRunResult;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * 评估上下文，包含实际运行结果和运行时信息
 */
@Data
@Builder
public class EvalContext {

    /**
     * Agent 运行结果
     */
    private AgentRunResult runResult;

    /**
     * 最终输出内容
     */
    private String output;

    /**
     * 工具调用记录
     */
    @Builder.Default
    private List<ToolCall> toolCalls = Collections.emptyList();

    /**
     * Token 用量
     */
    private TokenUsage tokenUsage;

    /**
     * 开始时间
     */
    private Instant startTime;

    /**
     * 结束时间
     */
    private Instant endTime;

    /**
     * 延迟（毫秒）
     */
    private Long latencyMs;

    /**
     * 工作空间路径
     */
    private String workspacePath;

    /**
     * 额外属性
     */
    @Builder.Default
    private Map<String, Object> attributes = Collections.emptyMap();

    /**
     * 工具调用记录
     */
    @Data
    @Builder
    public static class ToolCall {
        private String toolName;
        private String arguments;
        private String result;
        private Integer sequence;
        private Long durationMs;
    }

    /**
     * Token 用量
     */
    @Data
    @Builder
    public static class TokenUsage {
        private int inputTokens;
        private int outputTokens;
        private int cacheReadTokens;
        private int cacheWriteTokens;
        private int totalTokens;

        public static TokenUsage empty() {
            return TokenUsage.builder()
                    .inputTokens(0)
                    .outputTokens(0)
                    .cacheReadTokens(0)
                    .cacheWriteTokens(0)
                    .totalTokens(0)
                    .build();
        }
    }
}
