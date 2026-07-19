package ai.openagent.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.config.ConfigService.SkillEntry;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ConfigService 三级继承链单测（V9 M3，共享内存仓储夹具不触数据库）：
 * setting 类键字段级深合并、provider/model 类整对象替换、逐 scope 打码、
 * 写路径按身份落 scope（普通用户写不进 system scope）
 */
class ConfigScopeTest {

    private static final String ADMIN_ID = "admin-1";
    private static final String USER_ID = "user-1";

    private final InMemoryConfigRepository repository = new InMemoryConfigRepository();
    private final InMemoryAgentRepository agentRepository = new InMemoryAgentRepository();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConfigService service = new ConfigService(
            repository,
            agentRepository,
            objectMapper,
            new ModelSettings("kimi", "https://api.example", "sk-1234567890abcd", "kimi-k2.5", 0.6, 4096, null),
            new AgentProperties(8, Duration.ofMinutes(10), 80000, 20, 2048),
            new SandboxProperties(false, "python:3.12-slim", "1", "512m", "bridge"));

    @AfterEach
    void clearIdentity() {
        RequestContext.clear();
    }

    private static void as(String userId, String role) {
        RequestContext.set(new RequestIdentity(userId, null, null, role, "test"));
    }

    private static void asAdmin() {
        as(ADMIN_ID, "super_admin");
    }

    private static void asUser() {
        as(USER_ID, "user");
    }

    @Test
    void settingKeysMergeFieldLevelUserOverSystem() {
        asAdmin();
        service.patchAgentDefaults(Map.of("model", "sys-model", "maxTokens", 1000, "temperature", 0.5));

        asUser();
        service.patchAgentDefaults(Map.of("maxTokens", 2000));

        // 用户视图：maxTokens 取 user 覆盖，model/temperature 继承 system
        ConfigService.AgentDefaults userDefaults = service.agentDefaults();
        assertEquals("sys-model", userDefaults.model());
        assertEquals(2000, userDefaults.maxTokens());
        assertEquals(0.5, userDefaults.temperature());

        // 管理员视图（无 user 行）：system 原样
        asAdmin();
        ConfigService.AgentDefaults adminDefaults = service.agentDefaults();
        assertEquals(1000, adminDefaults.maxTokens());
        assertEquals("sys-model", adminDefaults.model());
    }

    @Test
    void skillEnabledResolvesAgentThenOwnerUserThenSystem() {
        agentRepository.add("agt-1", USER_ID);
        asAdmin();
        service.patchSkillEntries(null, Map.of("web-search", new SkillEntry(true, null, null)));

        // 属主 user scope 覆盖 system
        asUser();
        service.patchSkillEntries(null, Map.of("web-search", new SkillEntry(false, null, null)));
        assertFalse(service.skillEnabled("agt-1", "web-search"));

        // agent scope 覆盖属主 user scope
        service.patchSkillEntries("agt-1", Map.of("web-search", new SkillEntry(true, null, null)));
        assertTrue(service.skillEnabled("agt-1", "web-search"));

        // 无条目技能默认启用
        assertTrue(service.skillEnabled("agt-1", "other-skill"));
    }

    @Test
    void providerClassKeysReplaceWholeObject() throws Exception {
        // system 与用户各自配了同名 provider 键（模拟 provider/model 类配置行）
        repository.upsert(
                ConfigRepository.SCOPE_SYSTEM,
                "",
                "providers.openai",
                objectMapper.writeValueAsString(Map.of("apiKey", "sys-key", "apiBase", "https://sys.example")));
        repository.upsert(
                ConfigRepository.SCOPE_USER,
                USER_ID,
                "providers.openai",
                objectMapper.writeValueAsString(Map.of("apiKey", "user-key")));

        // user 行整对象生效：不再继承 system 的 apiBase
        asUser();
        Map<String, Object> merged = service.mergedMap("providers.openai");
        assertEquals("user-key", merged.get("apiKey"));
        assertNull(merged.get("apiBase"));

        // 无 user 行时回退 system 整行
        asAdmin();
        Map<String, Object> systemView = service.mergedMap("providers.openai");
        assertEquals("sys-key", systemView.get("apiKey"));
        assertEquals("https://sys.example", systemView.get("apiBase"));
    }

    @Test
    void ordinaryUserWritesLandInOwnUserScopeOnly() {
        asUser();
        service.patchAgentDefaults(Map.of("model", "user-model"));
        service.patchPrefs(Map.of("timezone", "Asia/Shanghai"));
        service.patchSandbox(true);

        // system scope 无行：普通用户写不进 system scope（静默落自己的 user scope）
        assertTrue(repository.get(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_AGENTS_DEFAULTS).isEmpty());
        assertTrue(repository.get(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_PREFS).isEmpty());
        assertTrue(repository.get(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_SANDBOX).isEmpty());
        assertTrue(repository.get(ConfigRepository.SCOPE_USER, USER_ID, ConfigService.KEY_AGENTS_DEFAULTS).isPresent());
        assertTrue(repository.get(ConfigRepository.SCOPE_USER, USER_ID, ConfigService.KEY_PREFS).isPresent());
        assertTrue(repository.get(ConfigRepository.SCOPE_USER, USER_ID, ConfigService.KEY_SANDBOX).isPresent());
    }

    @Test
    void superAdminWritesLandInSystemScope() {
        asAdmin();
        service.patchAgentDefaults(Map.of("model", "sys-model"));

        assertTrue(repository.get(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_AGENTS_DEFAULTS).isPresent());
        assertTrue(repository.get(ConfigRepository.SCOPE_USER, ADMIN_ID, ConfigService.KEY_AGENTS_DEFAULTS).isEmpty());
    }

    @Test
    void maskedRewriteKeepsInheritanceAndPlaintextOverridesPerScope() {
        // system 配了技能密钥
        asAdmin();
        service.patchSkillEntries(
                null, Map.of("web-search", new SkillEntry(true, "sk-system-secret-0001", null)));

        // 用户回写 GET 所见的打码值 + 改 enabled：密钥不在 user scope 落值（继续继承）
        asUser();
        Map<String, SkillEntry> maskedView = service.maskedSkillEntries(service.skillEntries());
        assertEquals("sk-s****0001", maskedView.get("web-search").apiKey());
        service.patchSkillEntries(
                null,
                Map.of("web-search", new SkillEntry(false, "sk-s****0001", null)));

        Map<String, SkillEntry> merged = service.skillEntries();
        assertEquals(false, merged.get("web-search").enabled());
        assertEquals("sk-system-secret-0001", merged.get("web-search").apiKey());

        // 用户写入明文新密钥：覆盖发生在自己的 user scope，system 原值不动
        service.patchSkillEntries(
                null, Map.of("web-search", new SkillEntry(null, "sk-user-secret-9999", null)));
        assertEquals("sk-user-secret-9999", service.skillEntries().get("web-search").apiKey());
        asAdmin();
        assertEquals("sk-system-secret-0001", service.skillEntries().get("web-search").apiKey());
    }

    @Test
    void maskingAppliesToMergedViewPerIdentity() {
        asAdmin();
        service.patchSkillEntries(
                null, Map.of("s", new SkillEntry(true, "sk-system-secret-0001", null)));
        asUser();
        service.patchSkillEntries(
                null, Map.of("s", new SkillEntry(null, "sk-user-secret-9999", null)));

        // 逐 scope 生效：用户看到自己密钥的打码，管理员看到 system 密钥的打码
        assertEquals(
                "sk-u****9999",
                service.maskedSkillEntries(service.skillEntries()).get("s").apiKey());
        asAdmin();
        assertEquals(
                "sk-s****0001",
                service.maskedSkillEntries(service.skillEntries()).get("s").apiKey());
    }

    @Test
    void agentEntriesVisibilityIsScopedToOwner() {
        agentRepository.add("agt-mine", USER_ID);
        agentRepository.add("agt-foreign", "user-2");
        repository.upsert(
                ConfigRepository.SCOPE_AGENT,
                "agt-mine",
                ConfigService.KEY_SKILLS_AGENT_ENTRIES_PREFIX + "agt-mine",
                "{\"s\":{\"enabled\":true}}");
        repository.upsert(
                ConfigRepository.SCOPE_AGENT,
                "agt-foreign",
                ConfigService.KEY_SKILLS_AGENT_ENTRIES_PREFIX + "agt-foreign",
                "{\"s\":{\"enabled\":false}}");

        // 普通用户只见自己 agent 的覆盖
        asUser();
        Map<String, Map<String, SkillEntry>> userView = service.agentSkillEntries();
        assertTrue(userView.containsKey("agt-mine"));
        assertFalse(userView.containsKey("agt-foreign"));

        // super_admin 见全部
        asAdmin();
        Map<String, Map<String, SkillEntry>> adminView = service.agentSkillEntries();
        assertTrue(adminView.containsKey("agt-mine"));
        assertTrue(adminView.containsKey("agt-foreign"));
    }
}
