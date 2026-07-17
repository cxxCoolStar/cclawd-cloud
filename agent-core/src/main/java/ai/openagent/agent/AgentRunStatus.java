package ai.openagent.agent;

/**
 * Agent 运行状态（V2 方案第 6 章状态机的持久化投影）
 *
 * <p>
 * 领域枚举归属 agent-core：AgentKernel 的运行结果（{@link AgentRunResult}）
 * 与 bootstrap 的 agent_runs 持久化共用同一份状态词汇，避免字符串手写漂移。
 * 只有 {@link #isTerminal() 终态} 可以结束会话的活跃运行标记；
 * 服务进程重启时遗留的 RUNNING 由启动恢复逻辑标记为 INTERRUPTED
 * </p>
 */
public enum AgentRunStatus {

    /**
     * 已创建，尚未开始执行
     */
    CREATED,

    /**
     * 执行中（含模型调用与工具执行的全部中间状态）
     */
    RUNNING,

    /**
     * 正常完成
     */
    COMPLETED,

    /**
     * 执行失败（模型调用失败、持久化失败等）
     */
    FAILED,

    /**
     * 超出单次运行总超时
     */
    TIMED_OUT,

    /**
     * 达到工具迭代上限后经最终总结收尾
     */
    LIMIT_REACHED,

    /**
     * 进程重启时发现的遗留运行（V2 不做断点续跑）
     */
    INTERRUPTED;

    /**
     * 是否为终态
     */
    public boolean isTerminal() {
        return this != CREATED && this != RUNNING;
    }
}
