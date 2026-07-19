package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API Key 全链路测试（V9 M2）
 *
 * <p>
 * 覆盖：创建（明文只返回一次、库存散列、列表打码）、Bearer 认证、
 * agent 子集 scope（子集内 200 / 子集外 403、列表按子集过滤）、绑定
 * 他人 agent 404、删除后 401、跨用户删除 404
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/apikey-flow-test.db",
            "openagent.registration-open=true",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyFlowTest {

    static {
        new File("target/apikey-flow-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Cookie adminCookie;
    private Cookie userCookie;
    private String agent1;
    private String agent2;

    @BeforeAll
    void setup() throws Exception {
        adminCookie = register("boss-" + suffix); // 首用户 super_admin
        userCookie = register("user-" + suffix);
        agent1 = createAgent("agent-one");
        agent2 = createAgent("agent-two");
    }

    private Cookie register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("openagent_session");
    }

    private String createAgent(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents")
                        .cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("agent").path("id").asText();
    }

    @Test
    void scopedKeyLifecycleAndScopeEnforcement() throws Exception {
        // 创建：明文只在创建响应返回一次
        MvcResult created = mockMvc.perform(post("/api/apikeys")
                        .cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "scoped", "type": "agent", "agentIds": ["%s"]}
                                """.formatted(agent2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apikey.type").value("agent"))
                .andExpect(jsonPath("$.apikey.agents[0]").value(agent2))
                .andExpect(jsonPath("$.apikey.key", containsString("****")))
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String keyId = body.path("apikey").path("id").asText();
        String token = body.path("token").asText();
        org.junit.jupiter.api.Assertions.assertTrue(token.startsWith("oag_"), "明文 key 应带 oag_ 前缀");

        // 列表：打码、不回显明文（按 id 过滤，与测试方法执行顺序无关）
        mockMvc.perform(get("/api/apikeys").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apikeys[?(@.id=='" + keyId + "')].key",
                        org.hamcrest.Matchers.hasItem(containsString("****"))))
                .andExpect(jsonPath("$.apikeys[?(@.id=='" + keyId + "')].key",
                        org.hamcrest.Matchers.hasItem(not(containsString(token.substring(4))))));

        // scope 内：chat/agent 端点 200
        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", agent2).param("sessionId", "s1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agent2).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // scope 外：403
        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", agent1).param("sessionId", "s1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/agents/" + agent1).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/chat/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId": "%s", "sessionId": "s1", "message": "hi"}
                                """.formatted(agent1)))
                .andExpect(status().isForbidden());

        // 列表按 scope 过滤
        mockMvc.perform(get("/api/agents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(1))
                .andExpect(jsonPath("$.agents[0].id").value(agent2));

        // 使用后 lastUsedAt 已刷新
        mockMvc.perform(get("/api/apikeys").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apikeys[?(@.id=='" + keyId + "')].lastUsedAt",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.notNullValue())));

        // 跨用户删除：404（不暴露存在性）
        mockMvc.perform(delete("/api/apikeys/" + keyId).cookie(adminCookie))
                .andExpect(status().isNotFound());

        // 属主删除后 Bearer 失效
        mockMvc.perform(delete("/api/apikeys/" + keyId).cookie(userCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", agent2).param("sessionId", "s1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unscopedKeyAccessesAllOwnAgents() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/apikeys")
                        .cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"full\", \"type\": \"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apikey.type").value("user"))
                .andReturn();
        String token = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("token").asText();

        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", agent1).param("sessionId", "s1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 无 scope 限制但仍受归属约束：别人的 agent 404
        mockMvc.perform(get("/api/agents/default").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // 无效 key → 401
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer oag_deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindingForeignAgentIsRejected() throws Exception {
        // default agent 归属种子 local-user，当前用户绑定它按不存在处理
        mockMvc.perform(post("/api/apikeys")
                        .cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"bad\", \"type\": \"agent\", \"agentIds\": [\"default\"]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/apikeys")
                        .cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"user\"}"))
                .andExpect(status().isBadRequest());
    }
}
