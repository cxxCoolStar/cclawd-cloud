package ai.openagent.bootstrap.config;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
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
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 配置服务（V7 方案 3.1/3.2；V9 M3 三级继承链）
 *
 * <p>
 * 读侧为三级合并 {@code merged = agent ⊕ user ⊕ system}：setting 类键
 * （{@code agents.defaults} / {@code skills.entries} / {@code prefs} /
 * {@code sandbox}）字段级深合并，user 覆盖 system；provider/model 类键
 * （不在 setting 集合内的任意键）整对象替换——user 配了自己的值则不再继承
 * system。agent 级经 {@code skills.agentEntries.{agentId}} 键表达（agent scope），
 * 覆盖 user/system 的同名技能条目。无身份上下文（异步运行线程、内部调用）
 * 时读侧回落 system scope 视图。
 * </p>
 *
 * <p>
 * 写侧按当前身份落 scope：super_admin（含无身份的内部/测试调用）写 system
 * scope；普通用户写自己的 user scope——普通用户无从写入 system scope
 * （静默落 user scope 语义）。agent 级沿用 agentEntries 键，写 agent scope，
 * 归属校验在 Web 入口（ConfigController）完成。DB 值与属性默认值
 * （{@link ModelSettings} / {@link AgentProperties} / {@link SandboxProperties}）
 * 合并后回显。
 * </p>
 *
 * <p>
 * 密钥回显一律打码（{@link #maskSecret}），逐 scope 生效；POST 收到的值若
 * 仍是打码形态（与合并视图中的打码结果一致），不在本 scope 落值——保留本
 * scope 现状（无值即继续继承上级），明文新值才覆盖。
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

    /**
     * setting 类键：读路径字段级深合并；集合外的键按 provider/model 类整对象替换
     */
    private static final Set<String> SETTING_KEYS =
            Set.of(KEY_AGENTS_DEFAULTS, KEY_SKILLS_ENTRIES, KEY_PREFS, KEY_SANDBOX);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, SkillEntry>> SKILL_ENTRIES_TYPE = new TypeReference<>() {};

    private final ConfigRepository configRepository;
    private final AgentRepository agentRepository;
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
     * 写路径落点
     */
    private record WriteScope(String scope, String scopeId) {}

    /**
     * 读取 Agent 默认值：合并视图（user 覆盖 system）优先，缺省回退属性派生值
     */
    public AgentDefaults agentDefaults() {
        Map<String, Object> stored = mergedMap(KEY_AGENTS_DEFAULTS);
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
     * PATCH Agent 默认值：仅合入出现的已知字段，写当前身份的 scope
     */
    public void patchAgentDefaults(Map<String, Object> patch) {
        WriteScope scope = writeScope();
        Map<String, Object> stored = readScopeMap(scope, KEY_AGENTS_DEFAULTS);
        for (String field : List.of("model", "maxTokens", "temperature", "maxToolIterations")) {
            if (patch.get(field) != null) {
                stored.put(field, patch.get(field));
            }
        }
        configRepository.upsert(scope.scope(), scope.scopeId(), KEY_AGENTS_DEFAULTS, writeJson(stored));
    }

    /**
     * 读取全局技能配置（system ⊕ 当前用户 user scope 逐条目字段级合并，
     * 未打码，内部使用）；无身份上下文时为 system scope 视图
     */
    public Map<String, SkillEntry> skillEntries() {
        Map<String, SkillEntry> merged = readEntries(ConfigRepository.SCOPE_SYSTEM, "", KEY_SKILLS_ENTRIES);
        currentUserId().ifPresent(userId -> readEntries(ConfigRepository.SCOPE_USER, userId, KEY_SKILLS_ENTRIES)
                .forEach((name, userEntry) -> merged.merge(name, userEntry, ConfigService::mergeEntry)));
        return merged;
    }

    /**
     * 读取当前身份可见的 agent 技能覆盖（agentId → 条目，未打码，内部使用）。
     * super_admin 与无身份上下文（单机回落）可见全部；普通用户仅见自己 agent 的覆盖
     */
    public Map<String, Map<String, SkillEntry>> agentSkillEntries() {
        Set<String> visible = visibleAgentIds();
        Map<String, Map<String, SkillEntry>> result = new LinkedHashMap<>();
        configRepository
                .listByScopeAndPrefix(ConfigRepository.SCOPE_AGENT, KEY_SKILLS_AGENT_ENTRIES_PREFIX)
                .forEach((key, json) -> {
                    String agentId = key.substring(KEY_SKILLS_AGENT_ENTRIES_PREFIX.length());
                    if (visible == null || visible.contains(agentId)) {
                        result.put(agentId, readSkillEntries(json));
                    }
                });
        return result;
    }

    /**
     * 某 agent 某技能是否启用（V7 方案 3.3；V9 M3 三级链）：agent scope 覆盖条目的
     * enabled 优先，其次 agent 属主的 user scope 条目，再次 system scope 条目；
     * 均无条目默认启用
     */
    public boolean skillEnabled(String agentId, String name) {
        Boolean enabled = enabledOf(
                configRepository
                        .get(ConfigRepository.SCOPE_AGENT, agentId, KEY_SKILLS_AGENT_ENTRIES_PREFIX + agentId)
                        .map(this::readSkillEntries)
                        .orElseGet(LinkedHashMap::new),
                name);
        if (enabled != null) {
            return enabled;
        }
        String ownerId = agentRepository.findById(agentId).map(AgentRecord::userId).orElse(null);
        if (ownerId != null) {
            enabled = enabledOf(readEntries(ConfigRepository.SCOPE_USER, ownerId, KEY_SKILLS_ENTRIES), name);
            if (enabled != null) {
                return enabled;
            }
        }
        enabled = enabledOf(readEntries(ConfigRepository.SCOPE_SYSTEM, "", KEY_SKILLS_ENTRIES), name);
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
     * PATCH 技能配置（agentId 为 null 时写当前身份 scope 的全局 entries，
     * 否则写该 agent 的 agent scope 覆盖）。
     * 深合并：enabled/apiKey 仅在出现时更新，env 按键合并；
     * apiKey 与 env 值带逐 scope 打码回写保护
     */
    public void patchSkillEntries(String agentId, Map<String, SkillEntry> patch) {
        if (agentId != null) {
            patchAgentScopeEntries(agentId, patch);
            return;
        }
        WriteScope scope = writeScope();
        // 打码比较基准 = 当前身份的合并视图（即 GET 所见）；本 scope 行只存本 scope 的覆盖
        Map<String, SkillEntry> mergedView = skillEntries();
        Map<String, SkillEntry> stored = readEntries(scope.scope(), scope.scopeId(), KEY_SKILLS_ENTRIES);
        patch.forEach((name, incoming) -> {
            if (incoming == null) {
                return;
            }
            SkillEntry existing = stored.get(name);
            SkillEntry view = mergedView.get(name);
            Boolean enabled = incoming.enabled() != null
                    ? incoming.enabled()
                    : existing != null ? existing.enabled() : null;
            String apiKey = resolveScopedSecret(
                    incoming.apiKey(), existing != null ? existing.apiKey() : null, view != null ? view.apiKey() : null);
            stored.put(name, new SkillEntry(enabled, apiKey, mergeScopedEnv(existing, incoming.env(), view)));
        });
        configRepository.upsert(scope.scope(), scope.scopeId(), KEY_SKILLS_ENTRIES, writeJson(stored));
    }

    /**
     * PATCH agent scope 技能覆盖：打码比较基准为本 scope 行
     * （GET agentEntries 回显的即 agent scope 行的打码）
     */
    private void patchAgentScopeEntries(String agentId, Map<String, SkillEntry> patch) {
        String key = KEY_SKILLS_AGENT_ENTRIES_PREFIX + agentId;
        Map<String, SkillEntry> stored = readEntries(ConfigRepository.SCOPE_AGENT, agentId, key);
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
        configRepository.upsert(ConfigRepository.SCOPE_AGENT, agentId, key, writeJson(stored));
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
     * 读取用户偏好（合并视图，未出现子树时为空 map）
     */
    public Map<String, Object> prefs() {
        return mergedMap(KEY_PREFS);
    }

    /**
     * PATCH 用户偏好（Controller 入口：JSON 直接转换后按 Map 合并）
     */
    public void patchPrefs(JsonNode patch) {
        patchPrefs(objectMapper.convertValue(patch, MAP_TYPE));
    }

    /**
     * PATCH 用户偏好：timezone 需为合法 IANA 时区，非法抛 400；写当前身份的 scope
     */
    public void patchPrefs(Map<String, Object> patch) {
        if (patch.get("timezone") instanceof String timezone && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException error) {
                throw new ClientException("invalid timezone: " + timezone, BaseErrorCode.PARAM_VERIFY_ERROR);
            }
            WriteScope scope = writeScope();
            Map<String, Object> stored = readScopeMap(scope, KEY_PREFS);
            stored.put("timezone", timezone);
            configRepository.upsert(scope.scope(), scope.scopeId(), KEY_PREFS, writeJson(stored));
        }
    }

    /**
     * 沙箱 enabled 的合并视图覆盖值（user 覆盖 system；均未配置时为空）
     */
    public Optional<Boolean> sandboxEnabledOverride() {
        return mergedMap(KEY_SANDBOX).get("enabled") instanceof Boolean enabled
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
     * PATCH 沙箱配置：仅 enabled 可写，其余字段忽略（仍由环境变量配置）；
     * 写当前身份的 scope
     */
    public void patchSandbox(Boolean enabled) {
        if (enabled == null) {
            return;
        }
        WriteScope scope = writeScope();
        Map<String, Object> stored = readScopeMap(scope, KEY_SANDBOX);
        stored.put("enabled", enabled);
        configRepository.upsert(scope.scope(), scope.scopeId(), KEY_SANDBOX, writeJson(stored));
    }

    /**
     * 密钥打码：≤8 位 → {@code ****}，否则前 4 位 + {@code ****} + 后 4 位
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
     * 键名是否疑似密钥（检查是否包含 KEY、TOKEN、SECRET、PASSWORD、CREDENTIAL 等关键字）
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
     * 三级合并读取（包私有，供单测直接验证合并语义）：system 行 + 当前用户
     * user 行。setting 类键字段级深合并（user 覆盖 system）；其余键按
     * provider/model 类语义整对象替换（user 行存在即整体生效，不再继承 system）
     */
    Map<String, Object> mergedMap(String key) {
        Map<String, Object> system = readScopeMap(ConfigRepository.SCOPE_SYSTEM, "", key);
        Optional<String> userId = currentUserId();
        if (userId.isEmpty()) {
            return system;
        }
        Map<String, Object> user = readScopeMap(ConfigRepository.SCOPE_USER, userId.get(), key);
        if (!SETTING_KEYS.contains(key)) {
            return user.isEmpty() ? system : user;
        }
        Map<String, Object> merged = new LinkedHashMap<>(system);
        merged.putAll(user);
        return merged;
    }

    /**
     * 写路径落点：super_admin 写 system scope；普通用户写自己的 user scope；
     * 无身份上下文（内部/测试调用）回落 system scope（单机存量语义）
     */
    private WriteScope writeScope() {
        Optional<RequestIdentity> identity = RequestContext.current();
        if (identity.isPresent() && !identity.get().isPlatformAdmin() && identity.get().isAuthenticated()) {
            return new WriteScope(ConfigRepository.SCOPE_USER, identity.get().userId());
        }
        return new WriteScope(ConfigRepository.SCOPE_SYSTEM, "");
    }

    private static Optional<String> currentUserId() {
        return RequestContext.current()
                .map(RequestIdentity::userId)
                .filter(userId -> !userId.isBlank());
    }

    /**
     * 当前身份可见的 agent ID 集合；null 表示全部可见
     * （super_admin 或无身份上下文）
     */
    private Set<String> visibleAgentIds() {
        Optional<RequestIdentity> identity = RequestContext.current();
        if (identity.isEmpty() || identity.get().isPlatformAdmin()) {
            return null;
        }
        return agentRepository.listByUser(identity.get().userId()).stream()
                .map(AgentRecord::id)
                .collect(Collectors.toSet());
    }

    /**
     * setting 类条目的字段级合并：overlay（user）非空字段覆盖 base（system），
     * env 按键合并
     */
    private static SkillEntry mergeEntry(SkillEntry base, SkillEntry overlay) {
        Map<String, String> env;
        if (overlay.env() == null) {
            env = base.env();
        } else if (base.env() == null) {
            env = overlay.env();
        } else {
            env = new LinkedHashMap<>(base.env());
            env.putAll(overlay.env());
        }
        return new SkillEntry(
                overlay.enabled() != null ? overlay.enabled() : base.enabled(),
                overlay.apiKey() != null ? overlay.apiKey() : base.apiKey(),
                env);
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

    /**
     * 逐 scope 打码回写保护：回写值与合并视图（viewValue，GET 所见）的打码
     * 结果一致时，不在本 scope 落值——保留本 scope 现状（scopeStored 为 null
     * 即继续继承上级）；否则按本 scope 行的打码保护规则取值
     */
    private static String resolveScopedSecret(String incoming, String scopeStored, String viewValue) {
        if (incoming != null && incoming.contains("****") && viewValue != null && incoming.equals(maskSecret(viewValue))) {
            return scopeStored;
        }
        return resolveSecret(incoming, scopeStored);
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

    /**
     * env 的逐 scope 合并：本 scope 行 env + 出现的 incoming 键；打码回写
     * 与合并视图比较，保护命中时保留本 scope 现状（无值则不落键，继续继承）
     */
    private static Map<String, String> mergeScopedEnv(
            SkillEntry existing, Map<String, String> incomingEnv, SkillEntry view) {
        if (incomingEnv == null) {
            return existing != null ? existing.env() : null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null && existing.env() != null) {
            merged.putAll(existing.env());
        }
        Map<String, String> viewEnv = view != null && view.env() != null ? view.env() : Map.of();
        incomingEnv.forEach((name, value) -> {
            String resolved = resolveScopedSecret(value, merged.get(name), viewEnv.get(name));
            if (resolved != null) {
                merged.put(name, resolved);
            } else {
                merged.remove(name);
            }
        });
        return merged;
    }

    private Map<String, Object> readScopeMap(String scope, String scopeId, String key) {
        return configRepository
                .get(scope, scopeId, key)
                .map(json -> readJson(json, MAP_TYPE))
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> readScopeMap(WriteScope scope, String key) {
        return readScopeMap(scope.scope(), scope.scopeId(), key);
    }

    private Map<String, SkillEntry> readEntries(String scope, String scopeId, String key) {
        return configRepository
                .get(scope, scopeId, key)
                .map(this::readSkillEntries)
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
