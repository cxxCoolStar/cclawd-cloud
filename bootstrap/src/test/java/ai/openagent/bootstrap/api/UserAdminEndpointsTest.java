package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.hasItem;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 用户管理 RBAC 与注册开关测试（V9 M2）
 *
 * <p>
 * super_admin（测试注入的种子 local-user 会话）：/api/users CRUD、重置密码、
 * /api/admin/registration 读写均可用；普通用户调这些端点一律 403。
 * 停用账号立即失效（会话 401、登录 401）；不允许删除自己。
 * 本测试库不配 registration-open（默认关闭），注册开关走 PUT 打开后注册
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/user-admin-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
class UserAdminEndpointsTest {

    static {
        new File("target/user-admin-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void superAdminManagesUsersEndToEnd() throws Exception {
        // 不携带 cookie：TestAuthSessionFilter 注入种子 local-user（super_admin）
        String username = "managed-" + suffix;
        MvcResult created = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.role").value("user"))
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("user").path("id").asText();

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[*].username", hasItem("local")))
                .andExpect(jsonPath("$.users[*].username", hasItem(username)));

        // 用户名/邮箱重复 → 409
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "other-%s@test.invalid", "password": "password123"}
                                """.formatted(username, suffix)))
                .andExpect(status().isConflict());

        // 新用户可登录
        Cookie session = login(username, "password123");

        // 改角色 → super_admin
        mockMvc.perform(put("/api/users/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"super_admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("super_admin"));
        mockMvc.perform(put("/api/users/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"root\"}"))
                .andExpect(status().isBadRequest());

        // 重置密码 → 旧会话失效，新密码可登录
        mockMvc.perform(post("/api/users/" + userId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"newpassword9\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
        session = login(username, "newpassword9");

        // 停用 → 会话立即失效且无法再登录
        mockMvc.perform(put("/api/users/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"disabled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.status").value("disabled"));
        mockMvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "newpassword9"}
                                """.formatted(username)))
                .andExpect(status().isUnauthorized());

        // 不允许删除自己（种子 local-user 即当前会话用户）
        mockMvc.perform(delete("/api/users/local-user")).andExpect(status().isBadRequest());

        // 删除用户
        mockMvc.perform(delete("/api/users/" + userId)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/users/" + userId)).andExpect(status().isNotFound());
    }

    @Test
    void regularUserGets403OnAdminEndpoints() throws Exception {
        String username = "plain-" + suffix;
        MvcResult created = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "email": "%s@test.invalid", "password": "password123"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("user").path("id").asText();
        Cookie session = login(username, "password123");

        mockMvc.perform(get("/api/users").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "x-%s", "email": "x-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/" + userId)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"super_admin\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/" + userId).cookie(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users/" + userId + "/password")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"whatever123\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/registration").cookie(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/registration")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\": true}"))
                .andExpect(status().isForbidden());

        // 自助端点不受影响
        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(username));
    }

    @Test
    void registrationGateIsReadableAndWritableBySuperAdmin() throws Exception {
        // 先造一个密码用户，保证不处于"首用户引导"态（与测试方法执行顺序无关）
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "seed-%s", "email": "seed-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk());

        // 默认关闭（未配置 openagent.registration-open）
        mockMvc.perform(get("/api/admin/registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));

        // 关闭状态下注册 403（库内已有密码用户，不再是引导态）
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "gated-%s", "email": "gated-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isForbidden());

        // 打开 → 注册放行
        mockMvc.perform(put("/api/admin/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true));
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "gated-%s", "email": "gated-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk());

        // 关回 → 再次 403（持久化立即生效）
        mockMvc.perform(put("/api/admin/registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "gated2-%s", "email": "gated2-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isForbidden());
    }

    private Cookie login(String login, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login": "%s", "password": "%s"}
                                """.formatted(login, password)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("openagent_session");
    }
}
