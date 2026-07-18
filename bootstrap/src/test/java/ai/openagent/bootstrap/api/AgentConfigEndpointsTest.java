package ai.openagent.bootstrap.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agent 配置接口集成测试（V6：GET config / PUT mcpServers 整表替换）
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/agent-config-test.db",
            "openagent.model.api-key=test-key"
        })
@AutoConfigureMockMvc
class AgentConfigEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void putAndGetMcpServers() throws Exception {
        mockMvc.perform(put("/api/agents/default")
                        .contentType("application/json")
                        .content("""
                                {"mcpServers": {
                                  "docs": {"type": "http", "url": "http://127.0.0.1:9000/mcp",
                                           "headers": {"Authorization": "Bearer x"}},
                                  "fs": {"type": "stdio", "command": "node",
                                         "args": ["server.js"], "env": {"DEBUG": "1"}}
                                }}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpServers.docs.type").value("http"))
                .andExpect(jsonPath("$.mcpServers.docs.headers.Authorization").value("Bearer x"))
                .andExpect(jsonPath("$.mcpServers.fs.args[0]").value("server.js"));

        mockMvc.perform(get("/api/agents/default/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpServers.fs.command").value("node"))
                .andExpect(jsonPath("$.mcpServers.fs.env.DEBUG").value("1"));

        // 整表替换：再 PUT 只剩一个
        mockMvc.perform(put("/api/agents/default")
                        .contentType("application/json")
                        .content("""
                                {"mcpServers": {"only": {"type": "stdio", "command": "npx"}}}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/default/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpServers.only").exists())
                .andExpect(jsonPath("$.mcpServers.docs").doesNotExist());

        // 缺省 mcpServers 保持不动（前端 "omit to leave untouched" 语义）
        mockMvc.perform(put("/api/agents/default")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/default/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpServers.only").exists());

        // 显式 {} 清空
        mockMvc.perform(put("/api/agents/default")
                        .contentType("application/json")
                        .content("{\"mcpServers\": {}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/default/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mcpServers.only").doesNotExist());
    }

    @Test
    void rejectsInvalidServerType() throws Exception {
        mockMvc.perform(put("/api/agents/default")
                        .contentType("application/json")
                        .content("{\"mcpServers\": {\"bad\": {\"type\": \"websocket\"}}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAgentReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/no-such-agent/config"))
                .andExpect(status().isNotFound());
    }
}
