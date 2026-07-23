package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * /api/config 集成测试（V7 方案 4 M1：GET 形状、POST 后 GET 回读、secret 不泄漏）
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/config-endpoints-test.db",
            "openagent.model.provider=openai",
            "openagent.model.api-base=https://api.openai.com/v1",
            "openagent.model.api-key=sk-test-secret-key-0001",
            "openagent.model.name=kimi-k2.5",
            "openagent.model.temperature=0.7",
            "openagent.model.max-tokens=2048",
            "openagent.sandbox.docker-enabled=false",
            "openagent.sandbox.image=python:3.12-slim"
        })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void cleanDatabase() throws Exception {
        Files.deleteIfExists(Path.of("target/config-endpoints-test.db"));
    }

    @Test
    @Order(1)
    void getConfigReturnsFrontendShapeWithDefaultsAndMaskedApiKey() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.openai.apiKey").value("sk-t****0001"))
                .andExpect(jsonPath("$.providers.openai.apiBase").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.agents.defaults.model").value("kimi-k2.5"))
                .andExpect(jsonPath("$.agents.defaults.maxTokens").value(2048))
                .andExpect(jsonPath("$.agents.defaults.temperature").value(0.7))
                .andExpect(jsonPath("$.agents.defaults.maxToolIterations").value(8))
                .andExpect(jsonPath("$.channels").isEmpty())
                .andExpect(jsonPath("$.storage.type").value("sqlite"))
                .andExpect(jsonPath("$.sandbox.enabled").value(false))
                .andExpect(jsonPath("$.sandbox.image").value("python:3.12-slim"))
                .andExpect(jsonPath("$.sandbox.dockerImage").value("python:3.12-slim"))
                .andExpect(jsonPath("$.meta.systemDefaultModel").value("kimi-k2.5"))
                .andExpect(jsonPath("$.meta.serverTimezone").isString())
                .andExpect(content().string(not(containsString("sk-test-secret-key-0001"))));
    }

    @Test
    @Order(2)
    void postConfigPatchesSubtreesAndGetReadsBack() throws Exception {
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "agents": {"defaults": {"model": "kimi-k2.5-turbo", "temperature": 0.2}},
                                  "prefs": {"timezone": "Asia/Shanghai"},
                                  "sandbox": {"enabled": true},
                                  "skills": {
                                    "entries": {
                                      "web-search": {
                                        "enabled": true,
                                        "apiKey": "brave-secret-value-9999",
                                        "env": {"BRAVE_API_KEY": "brave-env-secret-8888", "BASE_URL": "https://api.example"}
                                      }
                                    },
                                    "agentEntries": {
                                      "default": {"web-search": {"enabled": false}}
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.defaults.model").value("kimi-k2.5-turbo"))
                .andExpect(jsonPath("$.agents.defaults.temperature").value(0.2))
                // 未 PATCH 的字段仍回退属性派生值
                .andExpect(jsonPath("$.agents.defaults.maxTokens").value(2048))
                .andExpect(jsonPath("$.prefs.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.sandbox.enabled").value(true))
                .andExpect(jsonPath("$.skills.entries.web-search.enabled").value(true))
                .andExpect(jsonPath("$.skills.entries.web-search.apiKey").value("brav****9999"))
                .andExpect(jsonPath("$.skills.entries.web-search.env.BRAVE_API_KEY").value("brav****8888"))
                .andExpect(
                        jsonPath("$.skills.entries.web-search.env.BASE_URL").value("https://api.example"))
                .andExpect(jsonPath("$.skills.agentEntries.default.web-search.enabled").value(false))
                // 响应任何位置不得泄漏明文密钥
                .andExpect(content().string(not(containsString("brave-secret-value-9999"))))
                .andExpect(content().string(not(containsString("brave-env-secret-8888"))));
    }

    @Test
    @Order(3)
    void postMaskedSecretKeepsStoredOriginal() throws Exception {
        // 回写 GET 拿到的打码值：已存密钥应保留
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "skills": {
                                    "entries": {
                                      "web-search": {
                                        "apiKey": "brav****9999",
                                        "env": {"BRAVE_API_KEY": "brav****8888"}
                                      }
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        // 打码回写保护生效：再次 GET 仍返回原密钥的打码（若被覆盖则打码形态会变）
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.entries.web-search.apiKey").value("brav****9999"))
                .andExpect(jsonPath("$.skills.entries.web-search.env.BRAVE_API_KEY").value("brav****8888"));
    }

    @Test
    @Order(4)
    void postInvalidTimezoneReturns400() throws Exception {
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefs\": {\"timezone\": \"Not/AZone\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        // 非法值未落库
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefs.timezone").value("Asia/Shanghai"));
    }
}
