package ai.openagent.bootstrap.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 通用配置仓储（V7 方案 3.1；V9 M3 起按 scope 三级寻址）
 *
 * <p>
 * 主键 {@code (scope, scope_id, config_key)}：system 级 scope_id 恒为空串，
 * user 级为用户 ID，agent 级为 agent ID（skills.agentEntries.{agentId} 键）。
 * 键命名空间见 V7 方案 3.1 表格：{@code agents.defaults} / {@code skills.entries} /
 * {@code skills.agentEntries.{agentId}} / {@code prefs} / {@code sandbox}
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ConfigRepository {

    /**
     * 平台级配置（scope_id 恒为空串）
     */
    public static final String SCOPE_SYSTEM = "system";

    /**
     * 用户级配置（scope_id 为用户 ID）
     */
    public static final String SCOPE_USER = "user";

    /**
     * Agent 级配置（scope_id 为 agent ID）
     */
    public static final String SCOPE_AGENT = "agent";

    private final JdbcTemplate jdbc;

    /**
     * 按 scope + 键读取配置 JSON
     */
    public Optional<String> get(String scope, String scopeId, String key) {
        return jdbc.query(
                        "SELECT config_value FROM configs WHERE scope = ? AND scope_id = ? AND config_key = ?",
                        (rs, row) -> rs.getString("config_value"),
                        scope,
                        scopeId,
                        key)
                .stream()
                .findFirst();
    }

    /**
     * 写入或更新配置（UPDATE-first，避免并发插入冲突）
     */
    public void upsert(String scope, String scopeId, String key, String json) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE configs SET config_value = ?, updated_at = ? WHERE scope = ? AND scope_id = ? AND config_key = ?",
                json,
                now,
                scope,
                scopeId,
                key);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO configs (scope, scope_id, config_key, config_value, updated_at) VALUES (?, ?, ?, ?, ?)",
                    scope,
                    scopeId,
                    key,
                    json,
                    now);
        }
    }

    /**
     * 删除某键的全部 scope 行（agent 删除时清理其 agentEntries 键）
     */
    public void delete(String key) {
        jdbc.update("DELETE FROM configs WHERE config_key = ?", key);
    }

    /**
     * 按 scope + 前缀列出键值（如 agent scope 下 {@code skills.agentEntries.}
     * 前缀收集全部 agent 覆盖）
     */
    public Map<String, String> listByScopeAndPrefix(String scope, String prefix) {
        return jdbc.query(
                "SELECT config_key, config_value FROM configs WHERE scope = ? AND config_key LIKE ? ORDER BY config_key",
                rs -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("config_key"), rs.getString("config_value"));
                    }
                    return result;
                },
                scope,
                prefix + "%");
    }
}
