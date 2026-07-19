package ai.openagent.bootstrap.persistence;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用户仓储
 */
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private static final String COLUMNS =
            "id, username, email, role, display_name, status, password_hash, avatar_url, agent_quota, created_at";

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, row) -> new UserRecord(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("role"),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getString("password_hash"),
            rs.getString("avatar_url"),
            rs.getInt("agent_quota"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    /**
     * 按 ID 查询用户
     */
    public Optional<UserRecord> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 按用户名查询用户
     */
    public Optional<UserRecord> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE username = ?", ROW_MAPPER, username).stream()
                .findFirst();
    }

    /**
     * 按邮箱查询用户
     */
    public Optional<UserRecord> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE email = ?", ROW_MAPPER, email).stream()
                .findFirst();
    }

    /**
     * 是否已有设置密码的用户（无密码用户 = 全新部署，首个注册走引导流程）
     */
    public boolean hasAnyPasswordUser() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE password_hash <> ''", Integer.class);
        return count != null && count > 0;
    }

    /**
     * 插入用户
     */
    public void insert(UserRecord user) {
        jdbc.update(
                "INSERT INTO users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                user.id(),
                user.username(),
                user.email(),
                user.role(),
                user.displayName(),
                user.status(),
                user.passwordHash(),
                user.avatarUrl(),
                user.agentQuota(),
                user.createdAt());
    }

    /**
     * 更新显示名与头像
     */
    public void updateProfile(String id, String displayName, String avatarUrl) {
        jdbc.update("UPDATE users SET display_name = ?, avatar_url = ? WHERE id = ?", displayName, avatarUrl, id);
    }

    /**
     * 更新密码散列
     */
    public void updatePasswordHash(String id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    /**
     * 列出全部用户（按创建时间升序，管理接口用）
     */
    public List<UserRecord> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM users ORDER BY created_at", ROW_MAPPER);
    }

    /**
     * 管理面更新：显示名 / 角色 / 状态 / 配额（null 字段不动）
     */
    public void updateAdmin(String id, String displayName, String role, String status, Integer agentQuota) {
        UserRecord current = findById(id).orElseThrow(() -> new IllegalArgumentException("user not found: " + id));
        jdbc.update(
                "UPDATE users SET display_name = ?, role = ?, status = ?, agent_quota = ? WHERE id = ?",
                displayName != null ? displayName : current.displayName(),
                role != null ? role : current.role(),
                status != null ? status : current.status(),
                agentQuota != null ? agentQuota : current.agentQuota(),
                id);
    }

    /**
     * 删除用户
     */
    public void delete(String id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }
}
