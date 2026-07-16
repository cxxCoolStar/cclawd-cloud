package ai.openagent.agent.tool;

import java.util.Map;

public record ToolResult(boolean success, String content, Map<String, Object> metadata) {

    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

