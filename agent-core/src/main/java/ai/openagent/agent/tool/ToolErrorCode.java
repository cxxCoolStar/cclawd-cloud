package ai.openagent.agent.tool;

/**
 * 工具统一错误码（V2 方案 7.4 建议错误码的代码化）
 */
public final class ToolErrorCode {

    public static final String TOOL_NOT_FOUND = "TOOL_NOT_FOUND";
    public static final String TOOL_NOT_ENABLED = "TOOL_NOT_ENABLED";
    public static final String TOOL_ARGUMENT_INVALID = "TOOL_ARGUMENT_INVALID";
    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    public static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    public static final String WORKSPACE_PATH_FORBIDDEN = "WORKSPACE_PATH_FORBIDDEN";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String NETWORK_TARGET_FORBIDDEN = "NETWORK_TARGET_FORBIDDEN";
    public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";

    /**
     * 执行器漏返回结果时由 Kernel 合成的失败（V2 方案 6.1 规则 8）
     */
    public static final String TOOL_RESULT_MISSING = "TOOL_RESULT_MISSING";

    /**
     * hook 在 BEFORE_TOOL_CALL 拒绝执行（V2 方案 6.1：策略拒绝作为
     * observation 回灌模型，由模型决定后续，不是运行终态）
     */
    public static final String TOOL_CALL_REJECTED = "TOOL_CALL_REJECTED";

    private ToolErrorCode() {}
}
