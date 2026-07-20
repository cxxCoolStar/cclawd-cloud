package ai.openagent.bootstrap.mcp;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.persistence.AgentMcpServerRecord;
import ai.openagent.bootstrap.persistence.AgentMcpServerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端管理器
 *
 * <p>
 * 管理 Agent 的 MCP 服务器连接，提供工具发现和调用功能。
 * 工具名使用前缀格式 {@code mcp_<sanitizedServerName>_<tool>}，
 * 支持按 server 名称路由；按 server 懒连接、工具发现、失败驱逐。
 * 传输经官方 MCP Java SDK：stdio 子进程 + Streamable HTTP
 * </p>
 */
@Slf4j
@Component
public class McpClientManager {

    /**
     * MCP 连接/调用失败（上层映射为工具级失败结果）
     */
    public static class McpException extends RuntimeException {
        public McpException(String message, Throwable cause) {
            super(message, cause);
        }

        public McpException(String message) {
            super(message);
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final AgentMcpServerRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpClientManager(AgentMcpServerRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 发现 Agent 的全部 MCP 工具（每个 server 懒连接；连接失败的 server
     * 记 WARN 跳过，不影响其他 server 与内置工具）
     */
    public List<McpToolAdapter.DiscoveredTool> discoverTools(String agentId) {
        List<McpToolAdapter.DiscoveredTool> discovered = new ArrayList<>();
        for (AgentMcpServerRecord server : repository.listByAgent(agentId)) {
            try {
                McpSyncClient client = connect(agentId, server);
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    discovered.add(new McpToolAdapter.DiscoveredTool(
                            server.name(), tool.name(), tool.description(), schemaToMap(tool.inputSchema())));
                }
            } catch (RuntimeException error) {
                log.warn("[mcp] server 工具发现失败，已跳过，agentId={}, server={}, error={}",
                        agentId, server.name(), error.getMessage());
            }
        }
        return discovered;
    }

    /**
     * 调用 MCP 工具
     * <p>
     * 按 server 名称和工具名路由调用指定 MCP 工具。
     * isError=true 时映射为失败结果；发生异常时驱逐客户端，下次调用将重试连接。
     * </p>
     */
    public ToolResult callTool(String agentId, String serverName, String toolName, String argumentsJson) {
        AgentMcpServerRecord server = repository.listByAgent(agentId).stream()
                .filter(record -> record.name().equals(serverName))
                .findFirst()
                .orElseThrow(() -> new McpException("mcp server not configured: " + serverName));
        try {
            McpSyncClient client = connect(agentId, server);
            Map<String, Object> arguments = objectMapper.readValue(
                    argumentsJson, new TypeReference<>() {});
            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            String text = extractText(result);
            return Boolean.TRUE.equals(result.isError())
                    ? ToolResult.failure("MCP_TOOL_ERROR", text)
                    : ToolResult.success(text);
        } catch (McpException error) {
            throw error;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            evict(agentId, serverName);
            throw new McpException("mcp call failed: " + error.getMessage(), error);
        }
    }

    /**
     * 配置变更后驱逐缓存客户端（PUT mcpServers 时调用）
     */
    public void evictAgent(String agentId) {
        clients.keySet().removeIf(key -> {
            if (key.startsWith(agentId + "\n")) {
                closeQuietly(clients.remove(key));
                return true;
            }
            return false;
        });
    }

    /**
     * 生成工具名前缀
     * <p>
     * 格式：{@code mcp_<sanitizedServer>_<tool>}，
     * server 名称中的非字母数字字符会被替换为下划线。
     * </p>
     */
    public static String prefixToolName(String serverName, String toolName) {
        return "mcp_" + serverName.replaceAll("[^a-zA-Z0-9_]", "_") + "_" + toolName;
    }

    /**
     * 懒连接（缓存复用；连接失败驱逐后抛错，下次调用重试）
     */
    private McpSyncClient connect(String agentId, AgentMcpServerRecord server) {
        String key = agentId + "\n" + server.name();
        try {
            return clients.computeIfAbsent(key, ignored -> doConnect(server));
        } catch (RuntimeException error) {
            evict(agentId, server.name());
            throw new McpException("mcp connect failed [" + server.name() + "]: " + error.getMessage(), error);
        }
    }

    private McpSyncClient doConnect(AgentMcpServerRecord server) {
        McpClientTransport transport = switch (server.type()) {
            // UTF-8 传输（SDK StdioClientTransport 在 Windows 中文环境
            // 用平台默认编码读子进程输出，非 ASCII 内容乱码）
            case "stdio" -> new Utf8StdioClientTransport(
                    ServerParameters.builder(server.command())
                            .args(parseList(server.argsJson()))
                            .env(parseMap(server.envJson()))
                            .build(),
                    objectMapper);
            case "http" -> {
                HttpClientStreamableHttpTransport.Builder builder =
                        HttpClientStreamableHttpTransport.builder(server.url())
                                .connectTimeout(CONNECT_TIMEOUT);
                Map<String, String> headers = parseMap(server.headersJson());
                if (!headers.isEmpty()) {
                    builder.customizeRequest(request ->
                            headers.forEach(request::header));
                }
                yield builder.build();
            }
            default -> throw new McpException("unknown mcp server type: " + server.type());
        };
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(CONNECT_TIMEOUT)
                .build();
        client.initialize();
        log.info("[mcp] server 已连接，server={}, type={}", server.name(), server.type());
        return client;
    }

    private void evict(String agentId, String serverName) {
        closeQuietly(clients.remove(agentId + "\n" + serverName));
    }

    private static void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // 尽力关闭
        }
    }

    /**
     * 提取结果文本（TextContent 拼接；无文本内容时退化为内容列表 JSON）
     */
    private String extractText(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        if (!text.isEmpty()) {
            return text.toString();
        }
        try {
            return objectMapper.writeValueAsString(result.content());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return String.valueOf(result.content());
        }
    }

    /**
     * inputSchema 透传为 ToolDescriptor 的 schema Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaToMap(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return Map.of("type", "object");
        }
        return objectMapper.convertValue(schema, new TypeReference<>() {});
    }

    private List<String> parseList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new McpException("invalid args_json: " + error.getMessage(), error);
        }
    }

    private Map<String, String> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new McpException("invalid env/headers json: " + error.getMessage(), error);
        }
    }
}
