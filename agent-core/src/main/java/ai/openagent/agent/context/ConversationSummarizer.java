package ai.openagent.agent.context;

/**
 * 会话总结端口（fastclaw compaction.go 中 provider.Chat 总结调用的抽象）
 *
 * <p>
 * 由 bootstrap 以 LLMService + provider 配置实现（总结参数 maxTokens /
 * temperature 对齐 fastclaw 的 2048 / 0.3）；实现失败应抛出异常，
 * 由 {@link ContextCompactor} 降级为仅裁剪
 * </p>
 */
@FunctionalInterface
public interface ConversationSummarizer {

    /**
     * 把旧消息文本总结为紧凑摘要
     *
     * @param conversationText 旧消息的 "[role] content" 逐行文本
     * @return 摘要文本
     */
    String summarize(String conversationText);
}
