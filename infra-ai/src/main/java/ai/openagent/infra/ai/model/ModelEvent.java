package ai.openagent.infra.ai.model;

/**
 * 模型流式调用中的增量事件
 *
 * <p>
 * 只承载实时展示所需的增量；tool call 分片在 infra-ai 内部聚合，
 * 不作为事件外泄（聚合结果见 {@link ModelResponse.ToolCalls}），
 * 失败以异常表达，最终结果以 {@link ModelResponse} 表达
 * </p>
 */
public sealed interface ModelEvent {

    /**
     * 正文增量（对应 SSE content_delta）
     */
    record TextDelta(String text) implements ModelEvent {}

    /**
     * 思考过程增量（DeepSeek/Kimi 等 reasoning_content 字段）
     */
    record ReasoningDelta(String text) implements ModelEvent {}
}
