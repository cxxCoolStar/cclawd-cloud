package ai.openagent.bootstrap.mcp;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import java.util.Map;

/**
 * MCP 工具桥接：把一个 MCP server 工具适配为 AgentTool，
 * 命名格式为 {@code mcp_<server>_<tool>}，source=MCP，
 * 不经过 ToolCatalog 白名单与 agent_tools 启停控制——
 * server 配置存在即启用
 */
public class McpToolAdapter implements AgentTool {

    /**
     * 发现到的 MCP 工具（连接与描述信息）
     */
    public record DiscoveredTool(String serverName, String toolName, String description,
                                 Map<String, Object> inputSchema) {

        public String prefixedName() {
            return McpClientManager.prefixToolName(serverName, toolName);
        }

        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    prefixedName(),
                    description == null || description.isBlank()
                            ? "MCP tool " + toolName + " from server " + serverName
                            : description,
                    inputSchema == null ? Map.of("type", "object") : inputSchema,
                    ToolDescriptor.Source.MCP);
        }
    }

    private final DiscoveredTool tool;
    private final McpClientManager clientManager;

    public McpToolAdapter(DiscoveredTool tool, McpClientManager clientManager) {
        this.tool = tool;
        this.clientManager = clientManager;
    }

    @Override
    public ToolDescriptor descriptor() {
        return tool.descriptor();
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
        try {
            return clientManager.callTool(
                    context.agentId(), tool.serverName(), tool.toolName(), arguments.json());
        } catch (McpClientManager.McpException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, error.getMessage());
        }
    }
}
