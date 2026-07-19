package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * API Key 仓储
 */
@Repository
@RequiredArgsConstructor
public class ApiKeyRepository {

    private static final String COLUMNS = "id, user_id, name, key_hash, agent_ids, created_at, last_used_at";

    private static final RowMapper<ApiKeyRecord> ROW_MAPPER = (rs, row) -> new ApiKeyRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("name"),
            rs.getString("key_hash"),
            rs.getString("agent_ids"),
            rs.getLong("created_at"),
            rs.getObject("last_used_at") == null ? null : rs.getLong("last_used_at"));

    private final JdbcTemplate jdbc;

    /**
     * 插入 Key
     */
    public void insert(ApiKeyRecord key) {
        jdbc.update(
                "INSERT INTO api_keys (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                key.id(),
                key.userId(),
                key.name(),
                key.keyHash(),
                key.agentIdsJson(),
                key.createdAt(),
                key.lastUsedAt());
    }

    /**
     * 按散列查询（认证路径）
     */
    public Optional<ApiKeyRecord> findByHash(String keyHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM api_keys WHERE key_hash = ?", ROW_MAPPER, keyHash).stream()
                .findFirst();
    }

    /**
     * 查询用户的 Key 列表（按创建时间升序）
     */
    public List<ApiKeyRecord> listByUser(String userId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM api_keys WHERE user_id = ? ORDER BY created_at", ROW_MAPPER, userId);
    }

    /**
     * 删除用户的指定 Key；返回是否删除成功（不存在或不属于该用户返回 false）
     */
    public boolean delete(String id, String userId) {
        return jdbc.update("DELETE FROM api_keys WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    /**
     * 更新最近使用时间（认证成功时惰性刷新）
     */
    public void touchLastUsed(String id, long now) {
        jdbc.update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", now, id);
    }
}
