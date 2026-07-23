package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.persistence.AuthSessionRecord;
import ai.openagent.bootstrap.persistence.AuthSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 认证与用户管理端到端测试（V9 M1）
 *
 * <p>
 * 覆盖：首用户注册即 super_admin 且自动登录（Set-Cookie HttpOnly +
 * SameSite=Lax）、门控开放时后续注册为 user、错误密码 401、改资料/改密码、
 * 登出后会话失效、过期会话 401、未认证访问受保护端点 401、公开端点放行。
 * 测试库每次运行前删除重建（"首用户"语义依赖库内无密码用户）
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/auth-endpoints-test.db",
            "openagent.registration-open=true",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
class AuthEndpointsTest {

    static {
        new File("target/auth-endpoints-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthSessionRepository sessionRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void firstRegisteredUserBecomesSuperAdminAndIsLoggedIn() throws Exception {
        String username = "boss-" + suffix;
        MvcResult result = mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.role").value("super_admin"))
                .andExpect(jsonPath("$.authMethod").value("cookie"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("openagent_session=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andReturn();

        Cookie session = result.getResponse().getCookie("openagent_session");
        assertNotNull(session, "注册成功应自动登录（写会话 cookie）");
        assertTrue(session.isHttpOnly());

        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.role").value("super_admin"))
                .andExpect(jsonPath("$.authMethod").value("cookie"));
    }

    @Test
    void registerLoginUpdateMeAndChangePassword() throws Exception {
        String username = "user-" + suffix;
        String email = username + "@test.invalid";
        // 门控开放时后续注册为普通 user
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s", "password": "password123", "displayName": "Old Name"}
                                """.formatted(username, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user.role").value("user"))
                .andExpect(jsonPath("$.user.displayName").value("Old Name"));

        // 错误密码 → 401
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "wrong-password"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid username or password"));

        // login 字段支持邮箱
        MvcResult login = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "password123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user.username").value(username))
                .andReturn();
        Cookie session = login.getResponse().getCookie("openagent_session");
        assertNotNull(session);

        // 改资料（对齐前端 updateMe：{displayName, avatarUrl}）
        mockMvc.perform(put("/api/me")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"New Name\", \"avatarUrl\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("New Name"));

        // 旧密码错误 → 401；正确 → 修改成功并可用新密码登录
        mockMvc.perform(post("/api/me/password")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\": \"not-the-password\", \"newPassword\": \"newpassword9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("old password is incorrect"));
        mockMvc.perform(post("/api/me/password")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\": \"password123\", \"newPassword\": \"newpassword9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "newpassword9"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        String username = "out-" + suffix;
        MvcResult registered = mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = registered.getResponse().getCookie("openagent_session");
        assertNotNull(session);

        mockMvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/logout").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        // 登出后原 cookie 已失效（请求显式携带 cookie，不会被测试注入覆盖）
        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void expiredSessionIsRejectedAndEvicted() throws Exception {
        String username = "exp-" + suffix;
        MvcResult registered = mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("user").path("id").asText();

        long now = System.currentTimeMillis();
        String expiredToken = "expired-" + suffix;
        sessionRepository.insert(new AuthSessionRecord(expiredToken, userId, now - 100_000, now - 1_000));

        mockMvc.perform(get("/api/me").cookie(new Cookie("openagent_session", expiredToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));
        // 过期会话被惰性剔除
        assertTrue(sessionRepository.findValid(expiredToken, now).isEmpty());
    }

    @Test
    void unauthenticatedRequestsAreRejectedButPublicEndpointsPass() throws Exception {
        // 未携带会话（X-Test-No-Auth 跳过测试注入）访问受保护端点 → 401
        mockMvc.perform(get("/api/me").header(TestAuthSessionFilter.SKIP_HEADER, "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mockMvc.perform(get("/api/agents").header(TestAuthSessionFilter.SKIP_HEADER, "1"))
                .andExpect(status().isUnauthorized());

        // 白名单端点未认证可访问
        mockMvc.perform(get("/api/status").header(TestAuthSessionFilter.SKIP_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));
    }
}
