package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工具接口集成测试（V7 方案 3.4 M3：管理视图、启停 upsert、白名单校验、
 * registered 契约、/api/tools 占位形状与延期业务错误）
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/tool-endpoints-test.db",
            "openagent.model.api-key=test-key"
        })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void cleanDatabase() throws Exception {
        Files.deleteIfExists(Path.of("target/tool-endpoints-test.db"));
    }

    @Test
    @Order(1)
    void listToolsReturnsCatalogWithSeedDefaults() throws Exception {
        mockMvc.perform(get("/api/agents/default/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools", hasSize(10)))
                .andExpect(jsonPath("$.data.tools[*].source", everyItem(org.hamcrest.Matchers.is("builtin"))))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'list_dir')].enabled", hasItem(true)))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'list_dir')].riskLevel", hasItem("MEDIUM")))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'write_file')].enabled", hasItem(true)))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'write_file')].riskLevel", hasItem("HIGH")))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'exec')].enabled", hasItem(true)));
    }

    @Test
    @Order(2)
    void enableToolUpsertsAndReflectsInListAndRegistry() throws Exception {
        mockMvc.perform(put("/api/agents/default/tools/write_file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/agents/default/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[?(@.name == 'write_file')].enabled", hasItem(true)));

        // live registry 同步可见
        mockMvc.perform(get("/api/agents/default/tools/registered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[*].name", hasItem("write_file")));

        // 再禁用
        mockMvc.perform(put("/api/agents/default/tools/write_file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/default/tools/registered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[*].name", not(hasItem("write_file"))));
    }

    @Test
    @Order(3)
    void registeredToolsExposeFrontendContractShape() throws Exception {
        mockMvc.perform(get("/api/agents/default/tools/registered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tools[*].name", hasItem("read_file")))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'read_file')].source", hasItem("builtin")))
                .andExpect(jsonPath("$.data.tools[?(@.name == 'read_file')].description", hasItem(org.hamcrest.Matchers.isA(String.class))))
                // 上一步禁用的工具不出现在 live registry
                .andExpect(jsonPath("$.data.tools[*].name", not(hasItem("write_file"))));
    }

    @Test
    @Order(4)
    void toggleRejectsUnknownToolAndMcpPrefix() throws Exception {
        mockMvc.perform(put("/api/agents/default/tools/no_such_tool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/agents/default/tools/mcp_fs_read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("mcp server configuration")));
    }

    @Test
    @Order(5)
    void unknownAgentReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/no-such-agent/tools"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agents/no-such-agent/tools/registered"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/agents/no-such-agent/tools/read_file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(6)
    void globalToolsReturnEmptyCatalogShape() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.toolProviders").isMap())
                .andExpect(jsonPath("$.toolProviders").isEmpty())
                .andExpect(jsonPath("$.tools").isMap())
                .andExpect(jsonPath("$.tools").isEmpty());
    }

    @Test
    @Order(7)
    void putGlobalToolsReturnsFeatureNotAvailable() throws Exception {
        mockMvc.perform(put("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolProviders\": {}, \"tools\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("feature not available")));
    }
}
