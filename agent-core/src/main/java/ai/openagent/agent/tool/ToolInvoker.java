package ai.openagent.agent.tool;

import ai.openagent.infra.ai.model.ToolCall;

/**
 * 工具统一调用入口（V2 方案 3.1 工具框架）
 *
 * <p>
 * AgentKernel 只面向本端口发起工具调用；实现（bootstrap）统一包装
 * 白名单校验、参数 JSON 合法性、超时、结果截断、异常映射与
 * tool_executions 持久化。任何失败都以失败 {@link ToolResult}
 * 表达，不向 Kernel 抛异常——失败是 observation，不是运行终态
 * （V2 方案 6.1 规则 4）。ToolCall 复用 infra-ai 的定义
 * （20.2 问题 5：同名类冲突保留 infra-ai 一份）
 * </p>
 */
public interface ToolInvoker {

    ToolResult invoke(ToolCall call, ToolExecutionContext context);
}
