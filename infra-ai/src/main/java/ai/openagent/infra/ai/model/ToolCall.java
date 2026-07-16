package ai.openagent.infra.ai.model;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

