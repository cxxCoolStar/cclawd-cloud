package ai.openagent.bootstrap.tool.controller.vo;

/**
 * 已注册工具视图（GET /api/agents/{id}/tools/registered，
 * 前端 AgentRegisteredTool 契约：name/description/source，V7 方案 3.4）
 *
 * @param name        工具名（allowlist 使用的规范名）
 * @param description 一句话说明
 * @param source      来源：builtin / mcp / plugin
 */
public record RegisteredToolVO(String name, String description, String source) {}
