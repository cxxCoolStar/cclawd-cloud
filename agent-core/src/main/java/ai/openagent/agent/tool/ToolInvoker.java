package ai.openagent.agent.tool;

import ai.openagent.infra.ai.model.ToolCall;

/**
 * 工具统一调用入口（V2 方案 3.1 工具框架）
 *
 * <p>
 * ToolCall 复用 infra-ai 的定义（20.2 问题 5：同名类冲突保留 infra-ai
 * 一份）——arguments 为模型返回的原始 JSON 字符串，Schema 校验、超时、
 * 结果截断与异常映射在 M3 的实现中统一包装
 * </p>
 */
public interface ToolInvoker {

    ToolResult invoke(ToolCall call, ToolExecutionContext context);
}

