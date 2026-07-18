package ai.openagent.bootstrap.config;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 配置服务（V7 方案 3.1/3.2）
 *
 * <p>
 * 读侧：DB 值与属性默认值（{@link ModelSettings} / {@link AgentProperties} /
 * {@link SandboxProperties}）合并；写侧：按命名空间 PATCH 深合并，只动出现的子树。
 * 密钥回显一律打码（{@link #maskSecret}，对齐 fastclaw maskAPIKey）；
 * POST 收到的值若仍是打码形态（与已存值的打码结果一致），保留已存原值
 * （fastclaw mergeSkillEntry 中未落地的掩码回写保护，V7 真正实现）。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    /**
     * 配置键：agent 默认值 {model?, maxTokens?, temperature?, maxToolIterations?}
     */
    public static final String KEY_AGENTS_DEFAULTS = "agents.defaults";

    /**
     * 配置键：全局技能配置 {skillName: {enabled?, apiKey?, env?}}
     */
    public static final String KEY_SKILLS_ENTRIES = "skills.entries";

    /**
     * 配置键前缀：per-agent 技能覆盖，完整键为前缀 + agentId
     */
    public static final String KEY_SKILLS_AGENT_ENTRIES_PREFIX = "skills.agentEntries.";

    /**
     * 配置键：用户偏好 {timezone?}
     */
    public static final String KEY_PREFS = "prefs";

    /**
     * 配置键：沙箱 {enabled?}（仅 enabled 可写，其余由环境变量配置）
     */
    public static final String KEY_SANDBOX = "sandbox";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, SkillEntry>> SKILL_ENTRIES_TYPE = new TypeReference<>() {};

    private final ConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final ModelSettings modelSettings;
    private final AgentProperties agentProperties;
    private final SandboxProperties sandboxProperties;

    /**
     * Agent 默认值（GET agents.defaults 形状，全部已解析非空）
     */
    public record AgentDefaults(String model, Integer maxTokens, Double temperature, Integer maxToolIterations) {}

    /**
     * 技能配置条目（前端 SkillEntryCfg 形状）
     */
    public record SkillEntry(Boolean enabled, String apiKey, Map<String, String> env) {}

    /**
     * 读取 Agent 默认值：DB 值优先，缺省回退属性派生值
     */
    public AgentDefaults agentDefaults() {
        Map<String, Object> stored = readMap(KEY_AGENTS_DEFAULTS);
        String model = stored.get("model") instanceof String value && !value.isBlank()
                ? value
                : modelSettings.name();
        return new AgentDefaults(
                model,
                asInteger(stored.get("maxTokens"), modelSettings.maxTokens()),
                asDouble(stored.get("temperature"), modelSettings.temperature()),
                asInteger(stored.get("maxToolIterations"), agentProperties.maxToolIterations()));
    }

    /**
     * PATCH Agent 默认值（Controller 入口：JSON 直接转换后按 Map 合并）
     */
    public void patchAgentDefaults(JsonNode patch) {
        patchAgentDefaults(objectMapper.convertValue(patch, MAP_TYPE));
    }

    /**
     * PATCH Agent 默认值：仅合入出现的已知字段
     */
    public void patchAgentDefaults(Map<String, Object> patch) {
        Map<String, Object> stored = readMap(KEY_AGENTS_DEFAULTS);
        for (String field : List.of("model", "maxTokens", "temperature", "maxToolIterations")) {
            if (patch.get(field) != null) {
                stored.put(field, patch.get(field));
            }
        }
        configRepository.upsert(KEY_AGENTS_DEFAULTS, writeJson(stored));
    }

    /**
     * 读取全局技能配置（未打码，内部使用）
     */
    public Map<String, SkillEntry> skillEntries() {
        return configRepository
                .get(KEY_SKILLS_ENTRIES)
                .map(this::readSkillEntries)
                .orElseGet(LinkedHashMap::new);
    }

    /**
     * 读取全部 agent 技能覆盖（agentId → 条目，未打码，内部使用）
     */
    public Map<String, Map<String, SkillEntry>> agentSkillEntries() {
        Map<String, Map<String, SkillEntry>> result = new LinkedHashMap<>();
        configRepository.listByPrefix(KEY_SKILLS_AGENT_ENTRIES_PREFIX).forEach((key, json) ->
                result.put(key.substring(KEY_SKILLS_AGENT_ENTRIES_PREFIX.length()), readSkillEntries(json)));
        return result;
    }

    /**
     * 某 agent 某技能是否启用（V7 方案 3.3，对齐 fastclaw skills.go）：
     * per-agent 覆盖条目的 enabled 优先于全局 entries；均无条目默认启用
     */
    public boolean skillEnabled(String agentId, String name) {
        Boolean enabled = enabledOf(
                configRepository
                        .get(KEY_SKILLS_AGENT_ENTRIES_PREFIX + agentId)
                        .map(this::readSkillEntries)
                        .orElseGet(LinkedHashMap::new),
                name);
        if (enabled != null) {
            return enabled;
        }
        enabled = enabledOf(skillEntries(), name);
        return enabled == null || enabled;
    }

    /**
     * 条目中的 enabled 值；无条目或未设置时返回 null（视为未指定）
     */
    private static Boolean enabledOf(Map<String, SkillEntry> entries, String name) {
        SkillEntry entry = entries.get(name);
        return entry != null ? entry.enabled() : null;
    }

    /**
     * PATCH 技能配置（Controller 入口：JSON 直接转换后按条目合并）
     */
    public void patchSkillEntries(String agentId, JsonNode patch) {
        patchSkillEntries(agentId, objectMapper.convertValue(patch, SKILL_ENTRIES_TYPE));
    }

    /**
     * PATCH 技能配置（agentId 为 null 时写全局 entries，否则写 per-agent 覆盖）。
     * 深合并：enabled/apiKey 仅在出现时更新，env 按键合并；
     * apiKey 与 env 值带打码回写保护
     */
    public void patchSkillEntries(String agentId, Map<String, SkillEntry> patch) {
        String key = agentId == null ? KEY_SKILLS_ENTRIES : KEY_SKILLS_AGENT_ENTRIES_PREFIX + agentId;
        Map<String, SkillEntry> stored = configRepository
                .get(key)
                .map(this::readSkillEntries)
                .orElseGet(LinkedHashMap::new);
        patch.forEach((name, incoming) -> {
            if (incoming == null) {
                return;
            }
            SkillEntry existing = stored.get(name);
            Boolean enabled = incoming.enabled() != null
                    ? incoming.enabled()
                    : existing != null ? existing.enabled() : null;
            String apiKey = resolveSecret(incoming.apiKey(), existing != null ? existing.apiKey() : null);
            stored.put(name, new SkillEntry(enabled, apiKey, mergeEnv(existing, incoming.env())));
        });
        configRepository.upsert(key, writeJson(stored));
    }

    /**
     * 技能条目打码副本：apiKey 恒打码；env 中键名疑似密钥
     * （{@link #looksLikeSecret}）的值打码
     */
    public Map<String, SkillEntry> maskedSkillEntries(Map<String, SkillEntry> entries) {
        Map<String, SkillEntry> masked = new LinkedHashMap<>();
        entries.forEach((name, entry) -> {
            Map<String, String> env = null;
            if (entry.env() != null) {
                env = new LinkedHashMap<>();
                for (Map.Entry<String, String> envEntry : entry.env().entrySet()) {
                    env.put(
                            envEntry.getKey(),
                            looksLikeSecret(envEntry.getKey())
                                    ? maskSecret(envEntry.getValue())
                                    : envEntry.getValue());
                }
            }
            masked.put(
                    name,
                    new SkillEntry(
                            entry.enabled(),
                            entry.apiKey() == null ? null : maskSecret(entry.apiKey()),
                            env));
        });
        return masked;
    }

    /**
     * 读取用户偏好（未出现子树时为空 map）
     */
    public Map<String, Object> prefs() {
        return readMap(KEY_PREFS);
    }

    /**
     * PATCH 用户偏好（Controller 入口：JSON 直接转换后按 Map 合并）
     */
    public void patchPrefs(JsonNode patch) {
        patchPrefs(objectMapper.convertValue(patch, MAP_TYPE));
    }

    /**
     * PATCH 用户偏好：timezone 需为合法 IANA 时区，非法抛 400
     */
    public void patchPrefs(Map<String, Object> patch) {
        Map<String, Object> stored = readMap(KEY_PREFS);
        if (patch.get("timezone") instanceof String timezone && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException error) {
                throw new ClientException("invalid timezone: " + timezone, BaseErrorCode.PARAM_VERIFY_ERROR);
            }
            stored.put("timezone", timezone);
        }
        configRepository.upsert(KEY_PREFS, writeJson(stored));
    }

    /**
     * 沙箱 enabled 的 DB 覆盖值（未配置时为空）
     */
    public Optional<Boolean> sandboxEnabledOverride() {
        return readMap(KEY_SANDBOX).get("enabled") instanceof Boolean enabled
                ? Optional.of(enabled)
                : Optional.empty();
    }

    /**
     * 沙箱 enabled 生效值：DB 覆盖优先，缺省回退属性值
     */
    public boolean sandboxEnabled() {
        return sandboxEnabledOverride().orElse(sandboxProperties.dockerEnabled());
    }

    /**
     * PATCH 沙箱配置：仅 enabled 可写，其余字段忽略（仍由环境变量配置）
     */
    public void patchSandbox(Boolean enabled) {
        if (enabled == null) {
            return;
        }
        Map<String, Object> stored = readMap(KEY_SANDBOX);
        stored.put("enabled", enabled);
        configRepository.upsert(KEY_SANDBOX, writeJson(stored));
    }

    /**
     * 密钥打码（对齐 fastclaw maskAPIKey）：≤8 位 → {@code ****}，
     * 否则前 4 位 + {@code ****} + 后 4 位
     */
    public static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 键名是否疑似密钥（对齐 fastclaw looksLikeSecret）
     */
    public static boolean looksLikeSecret(String keyName) {
        String upper = keyName.toUpperCase(Locale.ROOT);
        return upper.contains("KEY")
                || upper.contains("TOKEN")
                || upper.contains("SECRET")
                || upper.contains("PASSWORD")
                || upper.contains("CREDENTIAL");
    }

    /**
     * 打码回写保护：回写值仍为打码形态（含 {@code ****} 且与已存值的
     * 打码结果一致）时保留已存原值
     */
    private static String resolveSecret(String incoming, String stored) {
        if (incoming != null && incoming.contains("****") && stored != null && incoming.equals(maskSecret(stored))) {
            return stored;
        }
        return incoming;
    }

    private static Map<String, String> mergeEnv(SkillEntry existing, Map<String, String> incomingEnv) {
        if (incomingEnv == null) {
            return existing != null ? existing.env() : null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null && existing.env() != null) {
            merged.putAll(existing.env());
        }
        incomingEnv.forEach((name, value) -> merged.put(name, resolveSecret(value, merged.get(name))));
        return merged;
    }

    private Map<String, Object> readMap(String key) {
        return configRepository
                .get(key)
                .map(json -> readJson(json, MAP_TYPE))
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, SkillEntry> readSkillEntries(String json) {
        return readJson(json, SKILL_ENTRIES_TYPE);
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new ServiceException("配置 JSON 解析失败", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ServiceException("配置 JSON 序列化失败", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private static Integer asInteger(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
