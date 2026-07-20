package ai.openagent.infra.ai.model;

import java.util.Map;
import java.util.Objects;

/**
 * 暴露给模型的工具定义
 *
 * <p>
 * 序列化为 OpenAI 兼容的 {@code {"type":"function","function":{...}}}
 * 结构；parameters 为 JSON Schema
 * </p>
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {

    public ToolDefinition {
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
