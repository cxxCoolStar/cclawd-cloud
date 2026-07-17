package ai.openagent.agent.tool;

import java.util.Objects;

/**
 * 工具执行结果（V2 方案 7.4）
 *
 * <p>
 * 工具不向上抛实现相关异常，统一映射为本结构；失败结果作为
 * observation 回传模型，由模型决定重试、换工具或结束
 * </p>
 *
 * @param success      是否成功
 * @param content      结果文本（已按 maxResultChars 截断）
 * @param errorCode    失败错误码（{@link ToolErrorCode}），成功为空
 * @param errorMessage 清理后的错误信息，成功为空
 * @param truncated    结果是否被截断
 * @param durationMs   执行耗时
 */
public record ToolResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        boolean truncated,
        long durationMs) {

    public ToolResult {
        content = Objects.requireNonNullElse(content, "");
        errorCode = Objects.requireNonNullElse(errorCode, "");
        errorMessage = Objects.requireNonNullElse(errorMessage, "");
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content, "", "", false, 0);
    }

    public static ToolResult failure(String errorCode, String errorMessage) {
        return new ToolResult(false, "", errorCode, errorMessage, false, 0);
    }

    /**
     * 回传模型的 observation 文本：成功给结果，失败给结构化错误描述
     */
    public String observation() {
        if (success) {
            return content;
        }
        return "Tool failed [" + errorCode + "]: " + errorMessage;
    }

    public ToolResult withDuration(long durationMs) {
        return new ToolResult(success, content, errorCode, errorMessage, truncated, durationMs);
    }

    public ToolResult truncatedTo(String truncatedContent) {
        return new ToolResult(success, truncatedContent, errorCode, errorMessage, true, durationMs);
    }
}
