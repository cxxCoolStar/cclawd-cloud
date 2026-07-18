package ai.openagent.bootstrap.tool.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 工具启停请求（PUT /api/agents/{id}/tools/{toolName}）
 */
public record ToolEnableRequest(@NotNull(message = "enabled required") Boolean enabled) {}
