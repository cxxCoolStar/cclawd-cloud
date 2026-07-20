package ai.openagent.agent.context;

/**
 * 会话总结端口
 *
 * <p>
 * 由 bootstrap 以 LLMService + provider 配置实现（总结参数 maxTokens=2048,
 * temperature=0.3）；实现失败应抛出异常，由 {@link ContextCompactor} 降级为仅裁剪
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
