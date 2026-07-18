package ai.openagent.bootstrap.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.ConfigService.SkillEntry;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.bootstrap.skill.config.SkillProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.exception.ClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillService 单测（V5 方案 M1/M2 行为清单 + V7 方案 3.3 启停过滤）
 */
class SkillServiceTest {

    /**
     * 内存版 ConfigRepository：按 LinkedHashMap 存取（同 ConfigServiceTest 写法）
     */
    private static final class StubConfigRepository extends ConfigRepository {
        private final Map<String, String> store = new LinkedHashMap<>();

        StubConfigRepository() {
            super(null);
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void upsert(String key, String json) {
            store.put(key, json);
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }

        @Override
        public Map<String, String> listByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            store.forEach((key, json) -> {
                if (key.startsWith(prefix)) {
                    result.put(key, json);
                }
            });
            return result;
        }
    }

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
        return service(skillsDir, workspaceRoot, configService());
    }

    private SkillService service(Path skillsDir, Path workspaceRoot, ConfigService configService) {
        return new SkillService(
                new SkillProperties(skillsDir.toString()),
                new ToolProperties(Duration.ofSeconds(30), 65536, workspaceRoot.toString(), 1048576, false, 1048576),
                configService);
    }

    private ConfigService configService() {
        return new ConfigService(
                new StubConfigRepository(),
                new ObjectMapper(),
                new ModelSettings("kimi", "https://api.example", "sk-1234567890abcd", "kimi-k2.5", 0.6, 4096, null),
                new AgentProperties(8, Duration.ofMinutes(10), 80000, 20, 2048),
                new SandboxProperties(false, "python:3.12-slim", "1", "512m", "bridge"));
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
    void loadAllFiltersGloballyDisabledSkill(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        writeSkill(global, "calc", "---\ndescription: calc things\n---\nbody");
        ConfigService configService = configService();
        configService.patchSkillEntries(null, Map.of("web-search", new SkillEntry(false, null, null)));
        SkillService service = service(global, temp.resolve("ws"), configService);

        List<SkillService.Skill> all = service.loadAll("default");
        assertEquals(List.of("calc"), all.stream().map(SkillService.Skill::name).toList());
        assertFalse(service.buildSkillsSummary("default").contains("web-search"));
    }

    @Test
    void agentOverrideReenablesGloballyDisabledSkill(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        ConfigService configService = configService();
        configService.patchSkillEntries(null, Map.of("web-search", new SkillEntry(false, null, null)));
        configService.patchSkillEntries("default", Map.of("web-search", new SkillEntry(true, null, null)));
        SkillService service = service(global, temp.resolve("ws"), configService);

        assertEquals(1, service.loadAll("default").size());
        assertTrue(service.loadSkillContent("default", "web-search").isPresent());
        // 无 per-agent 覆盖的其他 agent 仍被全局禁用过滤
        assertTrue(service.loadAll("other").isEmpty());
    }

    @Test
    void agentOverrideDisablesSkill(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        ConfigService configService = configService();
        configService.patchSkillEntries("default", Map.of("web-search", new SkillEntry(false, null, null)));
        SkillService service = service(global, temp.resolve("ws"), configService);

        assertTrue(service.loadAll("default").isEmpty());
        // 其他 agent 无覆盖，默认启用
        assertEquals(1, service.loadAll("other").size());
    }

    @Test
    void skillWithoutEntryDefaultsToEnabled(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        ConfigService configService = configService();
        // 只有无关技能的条目，web-search 无条目
        configService.patchSkillEntries(null, Map.of("other", new SkillEntry(false, null, null)));
        SkillService service = service(global, temp.resolve("ws"), configService);

        assertEquals(1, service.loadAll("default").size());
        assertTrue(service.loadSkillContent("default", "web-search").isPresent());
    }

    @Test
    void loadSkillContentReturnsEmptyForDisabledSkill(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        writeSkill(global, "web-search", FULL_SKILL);
        ConfigService configService = configService();
        configService.patchSkillEntries(null, Map.of("web-search", new SkillEntry(false, null, null)));
        SkillService service = service(global, temp.resolve("ws"), configService);

        assertTrue(service.loadSkillContent("default", "web-search").isEmpty());
    }

    @Test
    void listViewsDoNotFilterDisabledSkills(@TempDir Path temp) throws Exception {
        Path global = temp.resolve("skills");
        Path ws = temp.resolve("ws");
        writeSkill(global, "web-search", FULL_SKILL);
        writeSkill(ws.resolve("default").resolve("skills"), "agent-skill",
                "---\ndescription: agent only\n---\nbody");
        ConfigService configService = configService();
        configService.patchSkillEntries(null, Map.of("web-search", new SkillEntry(false, null, null)));
        configService.patchSkillEntries("default", Map.of("agent-skill", new SkillEntry(false, null, null)));
        SkillService service = service(global, ws, configService);

        assertEquals(1, service.listGlobal().size());
        assertEquals(1, service.listAgentSkills("default").size());
        // 运行时视图则两个都被过滤
        assertTrue(service.loadAll("default").isEmpty());
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
