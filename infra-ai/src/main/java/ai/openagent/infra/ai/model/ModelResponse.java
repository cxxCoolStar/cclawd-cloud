package ai.openagent.infra.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的最终结果（V2 方案 7.2 sealed ModelResponse）
 *
 * <p>
 * 两类结果对应 Agent 循环的两个分支：{@link Text} 表示普通文本完成，
 * {@link ToolCalls} 表示模型请求执行工具。两者都携带
 * rawAssistantJson 供历史重放原样回传；
 * ToolCalls 同时保留 content——模型可能在返回 tool calls 的同时输出
 * 正文，顺序必须保留（V2 方案 1.2 必测行为 5）
 * </p>
 */
public sealed interface ModelResponse {

    TokenUsage usage();

    String rawAssistantJson();

    /**
     * 纯文本完成
     */
    record Text(String content, TokenUsage usage, String rawAssistantJson) implements ModelResponse {

        public Text {
            content = Objects.requireNonNullElse(content, "");
            usage = Objects.requireNonNullElse(usage, TokenUsage.ZERO);
            rawAssistantJson = Objects.requireNonNullElse(rawAssistantJson, "");
        }
    }

    /**
     * 模型请求执行工具（calls 保持模型返回的稳定顺序）
     */
    record ToolCalls(List<ToolCall> calls, String content, TokenUsage usage, String rawAssistantJson)
            implements ModelResponse {

        public ToolCalls {
            calls = calls == null ? List.of() : List.copyOf(calls);
            content = Objects.requireNonNullElse(content, "");
            usage = Objects.requireNonNullElse(usage, TokenUsage.ZERO);
            rawAssistantJson = Objects.requireNonNullElse(rawAssistantJson, "");
        }
    }
}
