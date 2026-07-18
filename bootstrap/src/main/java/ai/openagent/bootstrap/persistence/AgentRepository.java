package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 智能体仓储
 */
@Repository
@RequiredArgsConstructor
public class AgentRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, user_id, name, description, provider_id, model, system_prompt, created_at, updated_at FROM agents";

    private static final RowMapper<AgentRecord> ROW_MAPPER = (rs, row) -> new AgentRecord(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("provider_id"),
            rs.getString("model"),
            rs.getString("system_prompt"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    /**
     * 查询用户的智能体列表（按创建时间升序）
     */
    public List<AgentRecord> listByUser(String userId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE user_id = ? ORDER BY created_at", ROW_MAPPER, userId);
    }

    /**
     * 按 ID 查询智能体
     */
    public Optional<AgentRecord> findById(String id) {
        return jdbc.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 判断智能体是否存在
     */
    public boolean exists(String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agents WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 插入智能体（仅种子数据使用）
     */
    public void insert(String id, String userId, String name, String description,
            String providerId, String model, String systemPrompt, long now) {
        jdbc.update(
                "INSERT INTO agents (id, user_id, name, description, provider_id, model, system_prompt, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, name, description, providerId, model, systemPrompt, now, now);
    }

    /**
     * 更新智能体名称与描述（PUT /api/agents 字段，V7 M3）
     */
    public void updateProfile(String id, String name, String description, long now) {
        jdbc.update(
                "UPDATE agents SET name = ?, description = ?, updated_at = ? WHERE id = ?",
                name, description, now, id);
    }

    /**
     * 更新智能体模型覆盖（空串回退种子默认值的逻辑由调用方处理，V7 M3）
     */
    public void updateModel(String id, String model, long now) {
        jdbc.update("UPDATE agents SET model = ?, updated_at = ? WHERE id = ?", model, now, id);
    }
}
