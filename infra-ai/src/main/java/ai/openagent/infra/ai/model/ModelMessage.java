package ai.openagent.infra.ai.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        Map<String, Object> extensions) {

    public ModelMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNullElse(content, "");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = Objects.requireNonNullElse(toolCallId, "");
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }
}

