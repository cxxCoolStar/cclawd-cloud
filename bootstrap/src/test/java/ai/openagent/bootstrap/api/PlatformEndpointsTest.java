package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "openagent.version=test-version",
            "spring.datasource.url=jdbc:sqlite:target/platform-endpoints-test.db",
            "openagent.sandbox.docker-enabled=true"
        })
@AutoConfigureMockMvc
class PlatformEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthProbesRemainCompatibleWithGoService() throws Exception {
        for (String endpoint : new String[] {"/healthz", "/livez", "/readyz"}) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok"));
        }
    }

    @Test
    void frontendChatRoutesUseTheStaticExport() throws Exception {
        mockMvc.perform(get("/agents/default/chat/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/agents/default/chat/index.html"));

        mockMvc.perform(get("/agents/default/chat/session-1/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/agents/default/chat/_/index.html"));
    }

    @Test
    void statusExposesFrontendContractAndV1Capabilities() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.port").value(18953))
                .andExpect(jsonPath("$.mode").value("local"))
                .andExpect(jsonPath("$.version").value("test-version"))
                .andExpect(jsonPath("$.uptime", matchesPattern("[0-9]+s")))
                .andExpect(jsonPath("$.agents[0].id").value("default"))
                .andExpect(jsonPath("$.channels", empty()))
                .andExpect(jsonPath("$.capabilities.channels").value(false))
                .andExpect(jsonPath("$.capabilities.dockerSandbox").value(true))
                .andExpect(jsonPath("$.capabilities.mcp").value(true))
                .andExpect(jsonPath("$.modelReady").value(false));
    }
}

