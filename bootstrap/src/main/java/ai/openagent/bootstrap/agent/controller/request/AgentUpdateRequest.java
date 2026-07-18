package ai.openagent.bootstrap.agent.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/**
 * Agent 更新请求（V6 最小实现：mcpServers 整表替换；V7 M3 增补
 * name/description/model 字段，null = 不动，model 为空串 = 清除覆盖
 * 回退种子默认值；其余前端字段本版本忽略——见 V7 方案 3.4）
 */
public record AgentUpdateRequest(
        String name,
        String description,
        String model,
        Map<String, @Valid McpServerRequest> mcpServers) {

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
