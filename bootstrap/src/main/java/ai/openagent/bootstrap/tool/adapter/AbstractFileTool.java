package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * 内置工具基类：参数 JSON 解析（Invoker 已保证是合法 JSON 对象）与
 * workspace 路径违规 / IO 异常的统一失败映射
 */
abstract class AbstractFileTool implements AgentTool {

    protected final ObjectMapper objectMapper;

    protected AbstractFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public final ToolResult execute(ToolArguments arguments, ai.openagent.agent.tool.ToolExecutionContext context) {
        JsonNode args;
        try {
            args = objectMapper.readTree(arguments.json());
        } catch (IOException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, "tool arguments is not valid JSON");
        }
        try {
            return run(args, context);
        } catch (WorkspacePaths.PathViolation violation) {
            return violation.toResult();
        } catch (IOException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, ioMessage(error));
        }
    }

    protected abstract ToolResult run(JsonNode args, ai.openagent.agent.tool.ToolExecutionContext context)
            throws IOException;

    /**
     * 必填字符串参数；缺失时返回 null，调用方给出工具化错误
     */
    protected static String requiredText(JsonNode args, String field) {
        JsonNode node = args.path(field);
        return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
    }

    protected static ToolResult missingArgument(String field) {
        return ToolResult.failure(ToolErrorCode.TOOL_ARGUMENT_INVALID, field + " is required");
    }

    /**
     * IO 异常消息不泄露宿主机绝对路径（方案 12.1）：只保留异常类名与文件名
     */
    private static String ioMessage(IOException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message.replace('\\', '/')
                .replaceAll("([A-Za-z]:)?(/[^\\s:]+)+/", "");
    }
}
