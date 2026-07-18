package ai.openagent.bootstrap.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 通用配置仓储（V7 方案 3.1，复用 V1 已建的 configs 表）
 *
 * <p>
 * 键命名空间见 V7 方案 3.1 表格：{@code agents.defaults} / {@code skills.entries} /
 * {@code skills.agentEntries.{agentId}} / {@code prefs} / {@code sandbox}
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ConfigRepository {

    private final JdbcTemplate jdbc;

    /**
     * 按键读取配置 JSON
     */
    public Optional<String> get(String key) {
        return jdbc.query(
                        "SELECT config_value FROM configs WHERE config_key = ?",
                        (rs, row) -> rs.getString("config_value"),
                        key)
                .stream()
                .findFirst();
    }

    /**
     * 写入或更新配置（UPDATE-first，避免并发插入冲突）
     */
    public void upsert(String key, String json) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE configs SET config_value = ?, updated_at = ? WHERE config_key = ?",
                json,
                now,
                key);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO configs (config_key, config_value, updated_at) VALUES (?, ?, ?)",
                    key,
                    json,
                    now);
        }
    }

    /**
     * 删除配置
     */
    public void delete(String key) {
        jdbc.update("DELETE FROM configs WHERE config_key = ?", key);
    }

    /**
     * 按前缀列出键值（如 {@code skills.agentEntries.} 前缀收集全部 agent 覆盖）
     */
    public Map<String, String> listByPrefix(String prefix) {
        return jdbc.query(
                "SELECT config_key, config_value FROM configs WHERE config_key LIKE ? ORDER BY config_key",
                rs -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("config_key"), rs.getString("config_value"));
                    }
                    return result;
                },
                prefix + "%");
    }
}
