package ai.openagent.agent.hook;

/**
 * Hook 挂载点
 */
public enum HookPoint {

    /**
     * system prompt 构建前（prompt 构建在 conversation.open() 内，
     * context 只带身份字段）
     */
    BEFORE_SYSTEM_PROMPT,

    /**
     * system prompt 构建后
     */
    AFTER_SYSTEM_PROMPT,

    /**
     * 模型调用前：可读写 HookContext.modelRequest，kernel 触发后读回生效
     */
    BEFORE_MODEL_CALL,

    /**
     * 模型调用后：成功带 modelResponse，失败带 error
     */
    AFTER_MODEL_CALL,

    /**
     * 工具调用前：唯一可 veto 的挂载点，HookContext.reject() 后 kernel
     * 跳过执行并合成 TOOL_CALL_REJECTED 失败结果回灌模型（V2 方案 6.1）
     */
    BEFORE_TOOL_CALL,

    /**
     * 工具调用后：带 toolResult，异常路径带 error
     */
    AFTER_TOOL_CALL,

    /**
     * 一轮运行结束后（emit Done 前）：带 iterations / toolCallCount /
     * runStatus，失败路径同样触发
     */
    POST_TURN
}
