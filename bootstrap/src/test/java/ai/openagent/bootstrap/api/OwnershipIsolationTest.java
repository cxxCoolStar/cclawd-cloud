package ai.openagent.bootstrap.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
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
 * 归属隔离越权负向测试矩阵（V9 M2 发布门禁）
 *
 * <p>
 * 用户 B 访问用户 A 的 agent 的每一类端点一律 404（不暴露存在性）；
 * super_admin 豁免路径正常（200）；不存在的 agent 与越权同口径 404。
 * 覆盖端点族：agents / agents.config / agents.files / agents.memory /
 * agents.skills / agents.tools / agents.sessions.history / chat.history /
 * chat.sessions / chat.subscribe / chat.stream
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/ownership-isolation-test.db",
            "openagent.registration-open=true",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnershipIsolationTest {

    static {
        new File("target/ownership-isolation-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Cookie userACookie;
    private Cookie userBCookie;
    private String agentId;

    @BeforeAll
    void setupUsersAndAgent() throws Exception {
        // 首个注册用户成为 super_admin（引导）；A/B 为普通用户
        register("boss-" + suffix);
        userACookie = register("user-a-" + suffix);
        userBCookie = register("user-b-" + suffix);

        MvcResult created = mockMvc.perform(post("/api/agents")
                        .cookie(userACookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"agent-of-a\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("agent").path("id").asText();
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

    @Test
    void ownerCanAccessOwnAgent() throws Exception {
        mockMvc.perform(get("/api/agents/" + agentId).cookie(userACookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.id").value(agentId));
        mockMvc.perform(get("/api/chat/sessions").param("agentId", agentId).cookie(userACookie))
                .andExpect(status().isOk());
    }

    @Test
    void foreignAgentEndpointsAllReturn404() throws Exception {
        // agents 族
        mockMvc.perform(get("/api/agents/" + agentId).cookie(userBCookie)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/agents/" + agentId)
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/agents/" + agentId).cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agents/" + agentId + "/config").cookie(userBCookie))
                .andExpect(status().isNotFound());

        // workspace 文件族
        mockMvc.perform(get("/api/agents/" + agentId + "/files").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agents/" + agentId + "/files/notes.txt").cookie(userBCookie))
                .andExpect(status().isNotFound());

        // memory 族
        mockMvc.perform(get("/api/agents/" + agentId + "/memory").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/agents/" + agentId + "/memory")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memory\": \"hijack\"}"))
                .andExpect(status().isNotFound());

        // skills 族
        mockMvc.perform(get("/api/agents/" + agentId + "/skills").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/agents/" + agentId + "/skills/whatever").cookie(userBCookie))
                .andExpect(status().isNotFound());

        // tools 族
        mockMvc.perform(get("/api/agents/" + agentId + "/tools").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agents/" + agentId + "/tools/registered").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/agents/" + agentId + "/tools/exec")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNotFound());

        // workspace 历史族
        mockMvc.perform(get("/api/agents/" + agentId + "/sessions/s1/history").cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/agents/" + agentId + "/sessions/s1/history/restore")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commit\": \"abc\"}"))
                .andExpect(status().isNotFound());

        // chat 族
        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", agentId).param("sessionId", "s1")
                        .cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/chat/sessions").param("agentId", agentId).cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/chat/subscribe")
                        .param("agentId", agentId).param("sessionId", "s1")
                        .cookie(userBCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/chat/stream")
                        .cookie(userBCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId": "%s", "sessionId": "s1", "message": "hi"}
                                """.formatted(agentId)))
                .andExpect(status().isNotFound());

        // 越权与不存在的 agent 同口径（不暴露存在性）
        mockMvc.perform(get("/api/agents/agt_notexist").cookie(userBCookie))
                .andExpect(status().isNotFound());
    }

    @Test
    void superAdminIsExemptFromOwnershipCheck() throws Exception {
        // 不携带 cookie 的请求由 TestAuthSessionFilter 注入种子 local-user
        // （super_admin）会话——可访问用户 A 的 agent
        mockMvc.perform(get("/api/agents/" + agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.id").value(agentId));
        mockMvc.perform(get("/api/chat/sessions").param("agentId", agentId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId + "/memory")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId + "/tools")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId + "/skills")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId + "/files")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId + "/sessions/s1/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/history").param("agentId", agentId).param("sessionId", "s1"))
                .andExpect(status().isOk());
    }
}
