package ai.openagent.bootstrap.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.persistence.AgentMcpServerRecord;
import ai.openagent.bootstrap.persistence.AgentMcpServerRepository;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MCP 客户端集成测试（V6 方案 5）
 *
 * <p>
 * 真实 stdio 子进程（Node 测试服务器）经官方 MCP SDK 连接：
 * 发现 echo 工具、prefixed 命名、调用拿到真实回显结果
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/mcp-manager-test.db",
            "openagent.model.api-key=test-key"
        })
class McpClientManagerIT {

    @Autowired
    private McpClientManager clientManager;

    @Autowired
    private AgentMcpServerRepository repository;

    @BeforeAll
    static void assumeNode() {
        boolean nodeAvailable = false;
        try {
            nodeAvailable = new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception ignored) {
            // node 不可用则跳过
        }
        Assumptions.assumeTrue(nodeAvailable, "需要 node 运行 MCP stdio 测试服务器");
    }

    @Test
    void discoversAndCallsStdioServerTool() {
        repository.replaceAll("default", List.of(new AgentMcpServerRecord(
                "default", "testserver", "stdio", "", "{}",
                "node", "[\"src/test/resources/mcp-stdio-server.js\"]", "{}", 0, 0)));

        List<McpToolAdapter.DiscoveredTool> tools = clientManager.discoverTools("default");
        assertEquals(1, tools.size());
        McpToolAdapter.DiscoveredTool echo = tools.get(0);
        assertEquals("echo", echo.toolName());
        assertEquals("mcp_testserver_echo", echo.prefixedName());
        assertEquals("Echo the input text back", echo.description());

        ToolResult result = clientManager.callTool("default", "testserver", "echo", "{\"text\":\"hello\"}");
        assertTrue(result.success());
        assertEquals("echo: hello", result.content());

        // UTF-8 回归：SDK StdioClientTransport 在 Windows 中文环境以平台
        // 默认编码读子进程输出导致中文乱码，自研 UTF-8 传输必须正确解码
        ToolResult chinese = clientManager.callTool("default", "testserver", "echo", "{\"text\":\"中文回显\"}");
        assertTrue(chinese.success());
        assertEquals("echo: 中文回显", chinese.content());
    }

    @Test
    void prefixSanitizesServerName() {
        assertEquals("mcp_my_server_echo", McpClientManager.prefixToolName("my-server", "echo"));
        assertEquals("mcp_a_b_c_x", McpClientManager.prefixToolName("a/b.c", "x"));
    }

    @Test
    void discoversAndCallsHttpServerTool() throws Exception {
        // 启动 Node Streamable HTTP 测试服务器并读出端口
        Process process = new ProcessBuilder(
                        "node", "src/test/resources/mcp-http-server.js")
                .redirectErrorStream(true)
                .start();
        try {
            String firstLine = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))
                    .readLine();
            assertTrue(firstLine != null && firstLine.startsWith("MCP_PORT="), "服务器应输出端口");
            String endpoint = "http://127.0.0.1:" + firstLine.substring("MCP_PORT=".length()) + "/mcp";

            repository.replaceAll("default", List.of(new AgentMcpServerRecord(
                    "default", "httpserver", "http", endpoint, "{}", "", "[]", "{}", 0, 0)));

            List<McpToolAdapter.DiscoveredTool> tools = clientManager.discoverTools("default");
            McpToolAdapter.DiscoveredTool upper = tools.stream()
                    .filter(tool -> tool.toolName().equals("upper"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未发现 upper 工具"));
            assertEquals("mcp_httpserver_upper", upper.prefixedName());

            ToolResult result = clientManager.callTool("default", "httpserver", "upper", "{\"text\":\"hello\"}");
            assertTrue(result.success());
            assertEquals("HELLO", result.content());
        } finally {
            process.destroyForcibly();
        }
    }
}
