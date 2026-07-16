package ai.openagent.infra.ai.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
        String provider,
        String model,
        List<ModelMessage> messages,
        List<Map<String, Object>> tools,
        Double temperature,
        Integer maxTokens,
        Map<String, Object> extensions) {

    public ModelRequest {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }
}

