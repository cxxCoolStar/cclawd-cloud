package ai.openagent.bootstrap.agent.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/**
 * Agent 更新请求（V6 最小实现：仅处理 mcpServers 整表替换，
 * 其他前端字段本版本忽略——见 V6 方案 3.1）
 */
public record AgentUpdateRequest(Map<String, @Valid McpServerRequest> mcpServers) {

    /**
     * MCP Server 配置（前端 MCPServerConfig 形状）
     */
    public record McpServerRequest(
            @NotBlank(message = "type required") @Pattern(regexp = "http|stdio", message = "type must be http or stdio")
                    String type,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env) {}
}
