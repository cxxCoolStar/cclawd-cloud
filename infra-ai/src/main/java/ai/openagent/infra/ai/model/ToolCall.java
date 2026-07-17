package ai.openagent.infra.ai.model;

import java.util.Objects;

/**
 * 模型请求的一次工具调用
 *
 * <p>
 * arguments 保持模型返回的原始 JSON 字符串（V2 方案第 8 章协议兼容规则），
 * 不在传输层解析为对象——JSON Schema 校验属于工具框架（M3）的职责，
 * 非法 JSON 也必须原样传递，由上层映射为 TOOL_ARGUMENT_INVALID
 * </p>
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNull(name, "name");
        arguments = Objects.requireNonNullElse(arguments, "");
    }
}
