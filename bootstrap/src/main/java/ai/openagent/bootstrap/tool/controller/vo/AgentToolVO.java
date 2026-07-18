package ai.openagent.bootstrap.tool.controller.vo;

import ai.openagent.bootstrap.tool.ToolCatalog;

/**
 * 工具管理视图（GET /api/agents/{id}/tools，V7 方案 3.4）
 *
 * @param name        工具名
 * @param description 一句话说明
 * @param riskLevel   风险级别（仅内置工具有；MCP 工具恒为 null）
 * @param enabled     是否启用（MCP 工具 server 配置即启用，恒为 true）
 * @param source      来源：builtin / mcp
 */
public record AgentToolVO(
        String name, String description, ToolCatalog.RiskLevel riskLevel, boolean enabled, String source) {}
