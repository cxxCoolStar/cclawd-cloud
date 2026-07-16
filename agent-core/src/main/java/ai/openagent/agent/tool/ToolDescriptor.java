package ai.openagent.agent.tool;

import java.util.Map;
import java.util.Objects;

public record ToolDescriptor(String name, String description, Map<String, Object> inputSchema, Source source) {

    public ToolDescriptor {
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        source = Objects.requireNonNull(source, "source");
    }

    public enum Source {
        BUILTIN,
        SKILL,
        MCP,
        PLUGIN
    }
}

