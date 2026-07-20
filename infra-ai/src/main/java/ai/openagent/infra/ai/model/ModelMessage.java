package ai.openagent.infra.ai.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 发送给模型的一条消息
 *
 * <p>
 * assistant 消息可携带 toolCalls；tool 消息以 toolCallId 与对应 tool call 配对；
 * rawAssistantJson 保存供应商返回的原始 assistant 消息 JSON，
 * 重放历史时优先原样回传（字节一致的前缀保证 prompt cache 命中，
 * 且 reasoning_content 等供应商扩展字段不丢失）
 * </p>
 */
public record ModelMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String rawAssistantJson) {

    public ModelMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNullElse(content, "");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = Objects.requireNonNullElse(toolCallId, "");
        rawAssistantJson = Objects.requireNonNullElse(rawAssistantJson, "");
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(Role.SYSTEM, content, null, null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(Role.USER, content, null, null, null);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(Role.ASSISTANT, content, null, null, null);
    }

    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage(Role.TOOL, content, null, toolCallId, null);
    }

    /**
     * 消息角色（wire 上序列化为小写）
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL;

        public String wireValue() {
            // Locale.ROOT：土耳其语区 JVM 下 "I".toLowerCase() 会产生无点 ı，
            // 导致 role 字符串非法被供应商拒绝
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
