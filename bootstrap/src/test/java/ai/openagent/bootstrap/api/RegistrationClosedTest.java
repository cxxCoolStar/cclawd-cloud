package ai.openagent.bootstrap.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import java.io.File;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 注册门控测试（V9 M1，registration-open 关闭的默认形态）
 *
 * <p>
 * 关闭门控下：首个注册用户（全新部署引导，库内无密码用户）仍可注册并
 * 成为 super_admin；其后注册一律 403。测试库每次运行前删除重建
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/registration-closed-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
class RegistrationClosedTest {

    static {
        new File("target/registration-closed-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void closedGateAllowsFirstUserButRejectsSecond() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 首个注册用户不受门控，自动成为 super_admin（全新部署可引导）
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "first-%s", "email": "first-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.user.role").value("super_admin"));

        // 已有密码用户后，门控关闭 → 403
        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "second-%s", "email": "second-%s@test.invalid", "password": "password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("registration is closed"));
    }
}
