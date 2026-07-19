package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * /api/config 三级继承链端点测试（V9 M3）
 *
 * <p>
 * super_admin 写 system scope、普通用户写自己的 user scope（写不进 system
 * scope——静默落 user scope 语义）；GET 回读为 agent ⊕ user ⊕ system 合并
 * 视图，形状不变；打码逐 scope 生效；skills.agentEntries 写 agent scope 且
 * 校验归属（越权 404），普通用户只见自己 agent 的覆盖
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/config-scope-endpoints-test.db",
            "openagent.registration-open=true",
            "openagent.model.provider=openai",
            "openagent.model.api-base=https://api.openai.com/v1",
            "openagent.model.api-key=sk-test-secret-key-0001",
            "openagent.model.name=kimi-k2.5",
            "openagent.sandbox.docker-enabled=false"
        })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigScopeEndpointsTest {

    static {
        new File("target/config-scope-endpoints-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Cookie adminCookie;
    private Cookie userACookie;
    private Cookie userBCookie;
    private String agentOfA;
    private String agentOfB;

    @BeforeAll
    void setupUsersAndAgents() throws Exception {
        // 首个注册用户成为 super_admin；A/B 为普通用户
        adminCookie = register("boss-" + suffix);
        userACookie = register("user-a-" + suffix);
        userBCookie = register("user-b-" + suffix);
        agentOfA = createAgent(userACookie, "agent-of-a");
        agentOfB = createAgent(userBCookie, "agent-of-b");
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

    private String createAgent(Cookie cookie, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("agent").path("id").asText();
    }

    @Test
    @Order(1)
    void superAdminWritesSystemScopeAndUserReadsMergedView() throws Exception {
        mockMvc.perform(post("/api/config")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agents": {"defaults": {"model": "sys-model", "maxTokens": 1000}},
                                  "skills": {"entries": {"web-search": {"enabled": true, "apiKey": "sk-system-secret-0001"}}}
                                }
                                """))
                .andExpect(status().isOk());

        // 普通用户读到 system 合并视图，密钥打码，响应不泄漏明文
        mockMvc.perform(get("/api/config").cookie(userBCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.defaults.model").value("sys-model"))
                .andExpect(jsonPath("$.agents.defaults.maxTokens").value(1000))
                .andExpect(jsonPath("$.skills.entries.web-search.enabled").value(true))
                .andExpect(jsonPath("$.skills.entries.web-search.apiKey").value("sk-s****0001"))
                .andExpect(content().string(not(containsString("sk-system-secret-0001"))));
    }

    @Test
    @Order(2)
    void ordinaryUserWriteLandsInUserScopeAndDoesNotTouchSystem() throws Exception {
        // 用户 B PATCH maxTokens + 回写打码密钥 + 改 enabled
        mockMvc.perform(post("/api/config")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agents": {"defaults": {"maxTokens": 2000}},
                                  "skills": {"entries": {"web-search": {"enabled": false, "apiKey": "sk-s****0001"}}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 用户 B 的合并视图：maxTokens 取 user 覆盖、model 继承 system；
        // 打码回写保护跨 scope 生效——密钥仍继承 system 原值
        mockMvc.perform(get("/api/config").cookie(userBCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.defaults.model").value("sys-model"))
                .andExpect(jsonPath("$.agents.defaults.maxTokens").value(2000))
                .andExpect(jsonPath("$.skills.entries.web-search.enabled").value(false))
                .andExpect(jsonPath("$.skills.entries.web-search.apiKey").value("sk-s****0001"))
                .andExpect(content().string(not(containsString("sk-system-secret-0001"))));

        // 写隔离：用户 A 的视图不受 B 影响（B 的写入没有进 system scope）
        mockMvc.perform(get("/api/config").cookie(userACookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.defaults.maxTokens").value(1000))
                .andExpect(jsonPath("$.skills.entries.web-search.enabled").value(true));
    }

    @Test
    @Order(3)
    void agentEntriesRequireOwnershipAndAreVisibleToOwnerOnly() throws Exception {
        // 用户 B 写用户 A 的 agent 配置：越权 404
        mockMvc.perform(post("/api/config")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": {\"agentEntries\": {\"" + agentOfA
                                + "\": {\"web-search\": {\"enabled\": true}}}}}"))
                .andExpect(status().isNotFound());

        // 用户 B 写自己 agent 的 agent scope 覆盖
        mockMvc.perform(post("/api/config")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": {\"agentEntries\": {\"" + agentOfB
                                + "\": {\"web-search\": {\"enabled\": true}}}}}"))
                .andExpect(status().isOk());

        // B 见自己 agent 的覆盖；A 的视图不含 B 的 agent 覆盖
        mockMvc.perform(get("/api/config").cookie(userBCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.agentEntries." + agentOfB + ".web-search.enabled").value(true));
        mockMvc.perform(get("/api/config").cookie(userACookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.agentEntries." + agentOfB).doesNotExist());

        // super_admin 可见全部 agent 覆盖
        mockMvc.perform(get("/api/config").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.agentEntries." + agentOfB + ".web-search.enabled").value(true));
    }
}
