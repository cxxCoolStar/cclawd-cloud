package ai.openagent.bootstrap.agent.controller.vo;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置文件视图（前端 AgentFileConfig 的最小形状，V6 只含 mcpServers）
 */
public record AgentConfigVO(Map<String, McpServerVO> mcpServers) {

    /**
     * MCP Server 配置（前端 MCPServerConfig 形状）
     */
    public record McpServerVO(
            String type,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env) {}
}
