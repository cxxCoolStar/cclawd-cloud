package ai.openagent.infra.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用请求
 *
 * <p>
 * tools 为空列表时请求体不携带 tools 字段（对齐 fastclaw：
 * {@code if len(tools) > 0}），用于普通聊天与迭代上限后的最终总结调用
 * </p>
 */
public record ModelRequest(
        ModelProviderConfig provider,
        String model,
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        Double temperature,
        Integer maxTokens) {

    public ModelRequest {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
