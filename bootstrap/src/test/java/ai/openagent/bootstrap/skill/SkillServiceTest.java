package ai.openagent.bootstrap.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.skill.config.SkillProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.exception.ClientException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillService 单测（V5 方案 M1/M2 行为清单）
 */
class SkillServiceTest {

    private static final String FULL_SKILL = """
            ---
            name: web-search
            description: Search the web and fetch pages. Use when the user asks for current info.
            metadata:
              fastclaw:
                env:
                  - name: TAVILY_API_KEY
                    description: search api key
                    required: true
                    secret: true
            ---
            # Web Search
            Run python {baseDir}/search.py "query".
            """;

    private SkillService service(Path skillsDir, Path workspaceRoot) {
        return new SkillService(
                new SkillProperties(skillsDir.toString()),
                new ToolProperties(Duration.ofSeconds(30), 65536, workspaceRoot.toString(), 1048576, false, 1048576));
    }

    private static void writeSkill(Path dir, String name, String content) throws Exception {
        Path skillDir = dir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
    }

    @Test
    void parsesFrontmatterAndEnvSpec(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        SkillService service = service(global, temp.resolve("ws"));

        List<SkillService.SkillInfo> list = service.listGlobal();
        assertEquals(1, list.size());
        SkillService.SkillInfo info = list.get(0);
        assertEquals("web-search", info.name());
        assertTrue(info.description().startsWith("Search the web"));
        assertEquals("skill", info.type());
        assertEquals(1, info.envSpec().size());
        assertEquals("TAVILY_API_KEY", info.envSpec().get(0).name());
        assertTrue(info.envSpec().get(0).required());
        assertTrue(info.envSpec().get(0).secret());
    }

    @Test
    void fallsBackToFirstContentLineWithoutFrontmatter(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "plain", "# Title\n\nDoes useful things.\nMore text.");
        SkillService service = service(global, temp.resolve("ws"));
        assertEquals("Does useful things.", service.listGlobal().get(0).description());
    }

    @Test
    void toleratesBrokenYaml(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "broken", "---\n: [not yaml\n---\nBody line here.");
        SkillService service = service(global, temp.resolve("ws"));
        assertEquals(1, service.listGlobal().size());
    }

    @Test
    void agentSkillShadowsGlobalWithSameName(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        Path ws = temp.resolve("ws");
        writeSkill(global, "dup", "---\ndescription: global version\n---\nbody");
        writeSkill(ws.resolve("default").resolve("skills"), "dup", "---\ndescription: agent version\n---\nbody");
        SkillService service = service(global, ws);

        List<SkillService.Skill> all = service.loadAll("default");
        assertEquals(1, all.size());
        assertEquals("agent version", all.get(0).description());
    }

    @Test
    void summaryContainsCatalogAndRules(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        SkillService service = service(global, temp.resolve("ws"));

        String summary = service.buildSkillsSummary("default");
        assertTrue(summary.contains("<skill_usage_rules>"));
        assertTrue(summary.contains("<skill_catalog>"));
        assertTrue(summary.contains("- web-search — Search the web and fetch pages."));
    }

    @Test
    void emptyCatalogProducesNoSummary(@TempDir Path temp) {
        SkillService service = service(temp.resolve("skills"), temp.resolve("ws"));
        assertEquals("", service.buildSkillsSummary("default"));
    }

    @Test
    void loadSkillContentPrefersAgentDirAndReplacesBaseDir(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        Path ws = temp.resolve("ws");
        writeSkill(global, "web-search", FULL_SKILL);
        SkillService service = service(global, ws);

        String content = service.loadSkillContent("default", "web-search").orElseThrow();
        assertTrue(content.contains("# Web Search"));
        assertFalse(content.contains("{baseDir}"));
        assertTrue(content.contains(global.resolve("web-search").toAbsolutePath().normalize().toString()));
        assertTrue(service.loadSkillContent("default", "nope").isEmpty());
    }

    @Test
    void validateNameRejectsTraversal() {
        SkillService.validateName("web-search");
        assertThrows(ClientException.class, () -> SkillService.validateName("../evil"));
        assertThrows(ClientException.class, () -> SkillService.validateName("a/b"));
    }

    @Test
    void installZipStripsTopLevelDir(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        SkillService service = service(global, temp.resolve("ws"));
        byte[] zip = zipOf(new String[][] {
            {"pack/SKILL.md", FULL_SKILL},
            {"pack/main.py", "print(1)"}
        });

        SkillService.InstallResult result =
                service.installZip(null, null, "pack.zip", new ByteArrayInputStream(zip));
        assertEquals("pack", result.name());
        assertTrue(Files.isRegularFile(global.resolve("pack").resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(global.resolve("pack").resolve("main.py")));
        assertEquals(2, result.files().size());
    }

    @Test
    void installZipRequiresSkillMdAtRoot(@TempDir Path temp) {
        SkillService service = service(temp.resolve("skills"), temp.resolve("ws"));
        byte[] zip = zipOf(new String[][] {{"readme.txt", "hi"}});
        assertThrows(ClientException.class,
                () -> service.installZip(null, null, "x.zip", new ByteArrayInputStream(zip)));
    }

    @Test
    void installZipInstallsIntoAgentDir(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        Path ws = temp.resolve("ws");
        SkillService service = service(global, ws);
        byte[] zip = zipOf(new String[][] {{"SKILL.md", FULL_SKILL}});

        SkillService.InstallResult result =
                service.installZip("default", "my-skill", "x.zip", new ByteArrayInputStream(zip));
        assertEquals("my-skill", result.name());
        assertTrue(Files.isRegularFile(ws.resolve("default").resolve("skills").resolve("my-skill").resolve("SKILL.md")));
    }

    @Test
    void deleteSkillRemovesDirectory(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "doomed", FULL_SKILL);
        SkillService service = service(global, temp.resolve("ws"));
        service.deleteSkill(null, "doomed");
        assertFalse(Files.exists(global.resolve("doomed")));
        assertThrows(ClientException.class, () -> service.deleteSkill(null, "doomed"));
    }

    @Test
    void firstSentenceTruncates() {
        assertEquals("Short one.", SkillService.firstSentence("Short one. Long tail here."));
        assertTrue(SkillService.firstSentence("x".repeat(500)).length() <= 140);
    }

    private static byte[] zipOf(String[][] entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String[] entry : entries) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        return buffer.toByteArray();
    }
}
