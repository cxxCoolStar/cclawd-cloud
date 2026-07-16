package ai.openagent.agent.tool;

import java.util.Map;

public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

