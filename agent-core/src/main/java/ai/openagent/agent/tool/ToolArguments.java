package ai.openagent.agent.tool;

import java.util.Objects;

/**
 * 工具调用参数（模型返回的原始 JSON 字符串）
 *
 * <p>
 * 保持字符串形态传递到工具实现，解析与 Schema 校验由统一 Invoker
 * 负责（非法 JSON 映射为 TOOL_ARGUMENT_INVALID，不进入工具实现）
 * </p>
 */
public record ToolArguments(String json) {

    public ToolArguments {
        json = Objects.requireNonNullElse(json, "");
    }
}
