package ai.openagent.agent.tool;

public interface ToolInvoker {

    ToolResult invoke(ToolCall call, ToolExecutionContext context);
}

