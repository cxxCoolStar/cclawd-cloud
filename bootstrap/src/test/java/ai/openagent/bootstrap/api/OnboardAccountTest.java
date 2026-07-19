package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import jakarta.servlet.http.Cookie;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Onboard 引导建账号测试（V9 M2，M1 遗留建议落地）
 *
 * <p>
 * 前端 onboard 页带账密字段，契约清晰：全新部署 onboard 携带
 * username/email/password 时创建 super_admin 账号，首个业务 agent 归属
 * 该账号；重复 onboard 不再建号（幂等）
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/onboard-account-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
class OnboardAccountTest {

    static {
        new File("target/onboard-account-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void onboardCreatesAdminAccountAndOwnedAgent() throws Exception {
        String username = "founder-" + suffix;
        mockMvc.perform(post("/api/onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123",
                                 "provider": "openai", "apiKey": "sk-test", "model": "test-model",
                                 "agentName": "first-bot"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 新建账号可登录，且为 super_admin
        MvcResult login = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "password123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("super_admin"))
                .andReturn();
        Cookie session = login.getResponse().getCookie("openagent_session");

        // 首个业务 agent 归属新账号
        mockMvc.perform(get("/api/agents").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[*].name", hasItem("first-bot")));

        // 重复 onboard 幂等：不再建号（第二组凭据无法登录）
        mockMvc.perform(post("/api/onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "second-%s", "email": "second-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "second-%s", "password": "password123"}
                                """.formatted(suffix)))
                .andExpect(status().isUnauthorized());
    }
}
