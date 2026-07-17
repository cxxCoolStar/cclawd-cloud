package ai.openagent.agent.tool;

/**
 * 单个工具端口（V2 方案 7.3）
 *
 * <p>
 * 实现方（M4 内置工具，bootstrap tool/adapter/）只关心业务逻辑：
 * 参数已通过 JSON 合法性校验，超时、结果截断、异常映射与持久化
 * 由统一 {@link ToolInvoker} 包装。实现内部失败时抛出任意异常均可，
 * Invoker 统一映射为 TOOL_EXECUTION_FAILED
 * </p>
 */
public interface AgentTool {

    ToolDescriptor descriptor();

    ToolResult execute(ToolArguments arguments, ToolExecutionContext context);
}
