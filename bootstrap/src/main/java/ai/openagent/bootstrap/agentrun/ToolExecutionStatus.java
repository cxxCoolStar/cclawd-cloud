package ai.openagent.bootstrap.agentrun;

/**
 * 工具执行状态
 */
public enum ToolExecutionStatus {

    /**
     * 模型已请求，等待执行
     */
    REQUESTED,

    /**
     * 执行中
     */
    RUNNING,

    /**
     * 执行成功
     */
    SUCCEEDED,

    /**
     * 执行失败（含参数校验失败）
     */
    FAILED,

    /**
     * 执行超时
     */
    TIMED_OUT;

    /**
     * 是否为终态
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }
}
