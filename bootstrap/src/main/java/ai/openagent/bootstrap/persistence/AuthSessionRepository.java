package ai.openagent.bootstrap.persistence;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 登录会话仓储
 *
 * <p>
 * 过期会话在查询时被惰性剔除（单机 SQLite 定位，无需定时清理任务）
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class AuthSessionRepository {

    private final JdbcTemplate jdbc;

    /**
     * 插入会话
     */
    public void insert(AuthSessionRecord session) {
        jdbc.update(
                "INSERT INTO auth_sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                session.token(),
                session.userId(),
                session.createdAt(),
                session.expiresAt());
    }

    /**
     * 按令牌查询未过期会话；过期行顺手删除并返回空
     */
    public Optional<AuthSessionRecord> findValid(String token, long now) {
        Optional<AuthSessionRecord> session = jdbc.query(
                        "SELECT token, user_id, created_at, expires_at FROM auth_sessions WHERE token = ?",
                        (rs, row) -> new AuthSessionRecord(
                                rs.getString("token"),
                                rs.getString("user_id"),
                                rs.getLong("created_at"),
                                rs.getLong("expires_at")),
                        token)
                .stream()
                .findFirst();
        if (session.isPresent() && session.get().expiresAt() <= now) {
            delete(token);
            return Optional.empty();
        }
        return session;
    }

    /**
     * 删除会话（登出）
     */
    public void delete(String token) {
        jdbc.update("DELETE FROM auth_sessions WHERE token = ?", token);
    }

    /**
     * 删除用户的全部会话（停用/删除账号、重置密码时强制重新登录）
     */
    public void deleteByUser(String userId) {
        jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
    }
}
