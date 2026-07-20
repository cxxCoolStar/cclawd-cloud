package ai.openagent.infra.ai.model;

/**
 * 一次模型调用的 token 用量
 *
 * <p>
 * inputTokens 为未命中缓存的输入 token（已扣除 cacheReadTokens），
 * 供应商未上报时各字段为 0
 * </p>
 */
public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    /**
     * 供应商未上报用量时的零值
     */
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);
}
