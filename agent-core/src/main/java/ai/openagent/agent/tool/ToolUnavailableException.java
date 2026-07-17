package ai.openagent.agent.tool;

import java.util.Objects;

/**
 * 工具不可用异常：模型请求了未注册或未启用的工具
 *
 * <p>
 * 由 {@link ToolRegistry#requireEnabled} 抛出，统一 Invoker 捕获后
 * 映射为失败 {@link ToolResult}（不向模型抛异常，作为 observation 回传）
 * </p>
 */
public class ToolUnavailableException extends RuntimeException {

    /**
     * 工具错误码（TOOL_NOT_FOUND / TOOL_NOT_ENABLED）
     */
    private final String errorCode;

    public ToolUnavailableException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public String errorCode() {
        return errorCode;
    }
}
