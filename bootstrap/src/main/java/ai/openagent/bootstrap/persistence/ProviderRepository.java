package ai.openagent.bootstrap.persistence;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 模型供应商仓储
 */
@Repository
@RequiredArgsConstructor
public class ProviderRepository {

    private final JdbcTemplate jdbc;

    /**
     * 按 ID 查询供应商
     */
    public Optional<ProviderRecord> findById(String id) {
        return jdbc.query(
                        "SELECT id, provider_type, name, api_base, api_key, model, temperature, max_tokens FROM providers WHERE id = ?",
                        (rs, row) -> new ProviderRecord(
                                rs.getString("id"),
                                rs.getString("provider_type"),
                                rs.getString("name"),
                                rs.getString("api_base"),
                                rs.getString("api_key"),
                                rs.getString("model"),
                                rs.getDouble("temperature"),
                                rs.getInt("max_tokens")),
                        id)
                .stream()
                .findFirst();
    }

    /**
     * 判断供应商是否存在
     */
    public boolean exists(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM providers WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 插入供应商（仅种子数据使用）
     */
    public void insert(String id, String type, String name, String apiBase, String apiKey,
            String model, double temperature, int maxTokens, long now) {
        jdbc.update(
                "INSERT INTO providers (id, provider_type, name, api_base, api_key, model, temperature, max_tokens, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, type, name, apiBase, apiKey, model, temperature, maxTokens, now, now);
    }

    /**
     * 按配置刷新供应商（仅种子数据使用，保持数据库与环境变量配置同步）
     */
    public void updateSettings(String id, String type, String apiBase, String apiKey,
            String model, double temperature, int maxTokens, long now) {
        jdbc.update(
                "UPDATE providers SET provider_type = ?, api_base = ?, api_key = ?, model = ?, temperature = ?, max_tokens = ?, updated_at = ? WHERE id = ?",
                type, apiBase, apiKey, model, temperature, maxTokens, now, id);
    }
}
