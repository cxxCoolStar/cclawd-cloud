package ai.openagent.bootstrap.persistence;

/**
 * Agent MCP Server 配置记录（形状对齐前端 MCPServerConfig）
 */
public record AgentMcpServerRecord(
        String agentId,
        String name,
        String type,
        String url,
        String headersJson,
        String command,
        String argsJson,
        String envJson,
        long createdAt,
        long updatedAt) {}
