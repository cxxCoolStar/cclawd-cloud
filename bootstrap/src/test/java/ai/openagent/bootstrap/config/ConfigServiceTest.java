package ai.openagent.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.config.ConfigService.SkillEntry;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.framework.exception.ClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ConfigService 单测（内存 StubConfigRepository，不触数据库，V7 方案 4 M1）
 */
class ConfigServiceTest {

    /**
     * 内存版 ConfigRepository：按 LinkedHashMap 存取
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

    private final StubConfigRepository repository = new StubConfigRepository();
    private final ConfigService service = new ConfigService(
            repository,
            new ObjectMapper(),
            new ModelSettings("kimi", "https://api.example", "sk-1234567890abcd", "kimi-k2.5", 0.6, 4096, null),
            new AgentProperties(8, Duration.ofMinutes(10), 80000, 20, 2048),
            new SandboxProperties(false, "python:3.12-slim", "1", "512m", "bridge"));

    @Test
    void agentDefaultsFallBackToModelSettingsWhenDbEmpty() {
        ConfigService.AgentDefaults defaults = service.agentDefaults();

        assertEquals("kimi-k2.5", defaults.model());
        assertEquals(4096, defaults.maxTokens());
        assertEquals(0.6, defaults.temperature());
        assertEquals(8, defaults.maxToolIterations());
    }

    @Test
    void patchAgentDefaultsMergesOnlyPresentFields() {
        service.patchAgentDefaults(Map.of("model", "kimi-k2.5-turbo", "temperature", 0.2));
        service.patchAgentDefaults(Map.of("maxTokens", 8192));

        ConfigService.AgentDefaults defaults = service.agentDefaults();
        assertEquals("kimi-k2.5-turbo", defaults.model());
        assertEquals(0.2, defaults.temperature());
        assertEquals(8192, defaults.maxTokens());
        // 未写过的字段仍回退属性派生值
        assertEquals(8, defaults.maxToolIterations());
    }

    @Test
    void maskSecretFollowsFastclawRules() {
        assertNull(ConfigService.maskSecret(null));
        assertEquals("", ConfigService.maskSecret(""));
        assertEquals("****", ConfigService.maskSecret("12345678"));
        assertEquals("****", ConfigService.maskSecret("short"));
        assertEquals("1234****wxyz", ConfigService.maskSecret("123456789wxyz"));
    }

    @Test
    void looksLikeSecretMatchesFastclawKeyNames() {
        assertTrue(ConfigService.looksLikeSecret("BRAVE_API_KEY"));
        assertTrue(ConfigService.looksLikeSecret("bot_token"));
        assertTrue(ConfigService.looksLikeSecret("clientSecret"));
        assertTrue(ConfigService.looksLikeSecret("db_password"));
        assertTrue(ConfigService.looksLikeSecret("AWS_CREDENTIALS"));
        assertFalse(ConfigService.looksLikeSecret("BASE_URL"));
    }

    @Test
    void patchSkillEntriesDeepMergesAndKeepsStoredSecretOnMaskedRewrite() {
        Map<String, SkillEntry> initial = new LinkedHashMap<>();
        initial.put(
                "web-search",
                new SkillEntry(true, "sk-real-secret-123456", Map.of("BRAVE_API_KEY", "brave-secret-0001")));
        service.patchSkillEntries(null, initial);

        // 回写打码值 + 仅出现 enabled：密钥保留原值，enabled 更新
        Map<String, SkillEntry> patch = new LinkedHashMap<>();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("BRAVE_API_KEY", "brav****0001");
        patch.put("web-search", new SkillEntry(false, "sk-r****3456", env));
        service.patchSkillEntries(null, patch);

        Map<String, SkillEntry> stored = service.skillEntries();
        SkillEntry entry = stored.get("web-search");
        assertEquals(false, entry.enabled());
        assertEquals("sk-real-secret-123456", entry.apiKey());
        assertEquals("brave-secret-0001", entry.env().get("BRAVE_API_KEY"));
    }

    @Test
    void patchSkillEntriesAcceptsNewPlaintextSecret() {
        service.patchSkillEntries(null, Map.of("s", new SkillEntry(null, "sk-first-value-9999", null)));
        service.patchSkillEntries(null, Map.of("s", new SkillEntry(null, "sk-second-value-8888", null)));

        assertEquals("sk-second-value-8888", service.skillEntries().get("s").apiKey());
    }

    @Test
    void maskedSkillEntriesMasksApiKeyAndSecretEnvOnly() {
        service.patchSkillEntries(
                null,
                Map.of(
                        "s",
                        new SkillEntry(true, "sk-real-secret-123456", Map.of(
                                "BRAVE_API_KEY", "brave-secret-0001",
                                "BASE_URL", "https://api.example"))));

        Map<String, SkillEntry> masked = service.maskedSkillEntries(service.skillEntries());
        SkillEntry entry = masked.get("s");
        assertEquals("sk-r****3456", entry.apiKey());
        assertEquals("brav****0001", entry.env().get("BRAVE_API_KEY"));
        assertEquals("https://api.example", entry.env().get("BASE_URL"));
        // 打码不影响已存原值
        assertEquals("sk-real-secret-123456", service.skillEntries().get("s").apiKey());
    }

    @Test
    void agentEntriesUsePrefixedKeys() {
        service.patchSkillEntries("agent-1", Map.of("s", new SkillEntry(false, null, null)));

        assertTrue(repository.get(ConfigService.KEY_SKILLS_AGENT_ENTRIES_PREFIX + "agent-1").isPresent());
        assertEquals(
                false,
                service.agentSkillEntries().get("agent-1").get("s").enabled());
    }

    @Test
    void patchPrefsValidatesTimezone() {
        service.patchPrefs(Map.of("timezone", "Asia/Shanghai"));
        assertEquals("Asia/Shanghai", service.prefs().get("timezone"));

        assertThrows(ClientException.class, () -> service.patchPrefs(Map.of("timezone", "Not/AZone")));
        // 非法值不落库
        assertEquals("Asia/Shanghai", service.prefs().get("timezone"));
    }

    @Test
    void sandboxEnabledPrefersDbOverrideThenProperty() {
        assertFalse(service.sandboxEnabled());
        assertTrue(service.sandboxEnabledOverride().isEmpty());

        service.patchSandbox(true);
        assertTrue(service.sandboxEnabled());
        assertEquals(Optional.of(true), service.sandboxEnabledOverride());

        service.patchSandbox(false);
        assertFalse(service.sandboxEnabled());
    }
}
