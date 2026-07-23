package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 技能管理接口集成测试（V5 方案 3.5）
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/skill-endpoints-test.db",
            "openagent.model.api-key=test-key",
            "openagent.tools.workspace-root=target/skill-ws",
            "openagent.skills.dir=target/skill-global"
        })
@AutoConfigureMockMvc
class SkillEndpointsTest {

    private static final String SKILL_MD = """
            ---
            name: demo-skill
            description: A demo skill for tests.
            ---
            # Demo
            Instructions here.
            """;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void seedSkills() throws Exception {
        Path global = Path.of("target", "skill-global", "demo-skill");
        Files.createDirectories(global);
        Files.writeString(global.resolve("SKILL.md"), SKILL_MD);
        Path agentSkill = Path.of("target", "skill-ws", "default", "skills", "agent-only");
        Files.createDirectories(agentSkill);
        Files.writeString(agentSkill.resolve("SKILL.md"), SKILL_MD.replace("demo-skill", "agent-only"));
    }

    @Test
    void listsGlobalSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("demo-skill"))
                .andExpect(jsonPath("$[0].description").value("A demo skill for tests."))
                .andExpect(jsonPath("$[0].type").value("skill"))
                .andExpect(jsonPath("$[0].location").isString());
    }

    @Test
    void listsAgentSkills() throws Exception {
        mockMvc.perform(get("/api/agents/default/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("agent-only"));
    }

    @Test
    void uploadsZipAndListsIt() throws Exception {
        MockMultipartFile zip = new MockMultipartFile(
                "file", "pack.zip", "application/zip", zipBytes());
        mockMvc.perform(multipart("/api/skills/upload").file(zip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("pack"))
                .andExpect(jsonPath("$.data.source").value("upload"));
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'pack')]").exists());
    }

    @Test
    void uploadWithoutSkillMdRejected() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("hi".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.zip", "application/zip", buffer.toByteArray());
        mockMvc.perform(multipart("/api/skills/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("SKILL.md")));
    }

    @Test
    void deletesAgentSkill() throws Exception {
        // 自造自删，与 listsAgentSkills 的预置数据互不干扰（测试顺序不保证）
        Path doomed = Path.of("target", "skill-ws", "default", "skills", "doomed");
        Files.createDirectories(doomed);
        Files.writeString(doomed.resolve("SKILL.md"), SKILL_MD);
        mockMvc.perform(delete("/api/agents/default/skills/doomed"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/default/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'doomed')]").doesNotExist());
    }

    private static byte[] zipBytes() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("pack/SKILL.md"));
            zip.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }
}
