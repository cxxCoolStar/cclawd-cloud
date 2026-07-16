package ai.openagent.bootstrap.persistence;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 用户仓储
 */
@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbc;

    /**
     * 按 ID 查询用户
     */
    public Optional<UserRecord> findById(String id) {
        return jdbc.query(
                        "SELECT id, username, email, role, display_name, status, created_at FROM users WHERE id = ?",
                        (rs, row) -> new UserRecord(
                                rs.getString("id"),
                                rs.getString("username"),
                                rs.getString("email"),
                                rs.getString("role"),
                                rs.getString("display_name"),
                                rs.getString("status"),
                                rs.getLong("created_at")),
                        id)
                .stream()
                .findFirst();
    }

    /**
     * 插入用户（仅种子数据使用）
     */
    public void insert(UserRecord user) {
        jdbc.update(
                "INSERT INTO users (id, username, email, role, display_name, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.id(),
                user.username(),
                user.email(),
                user.role(),
                user.displayName(),
                user.status(),
                user.createdAt());
    }
}
