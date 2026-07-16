package ai.openagent.infra.ai.model;

public sealed interface ModelEvent {

    record TextDelta(String text) implements ModelEvent {}

    record ReasoningDelta(String text) implements ModelEvent {}

    record ToolCallDelta(ToolCall toolCall) implements ModelEvent {}

    record UsageReceived(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens)
            implements ModelEvent {}

    record MessageCompleted(String finishReason) implements ModelEvent {}

    record ModelFailed(String code, String message, boolean retryable) implements ModelEvent {}
}

