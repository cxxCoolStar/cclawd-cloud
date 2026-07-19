package ai.openagent.bootstrap.identity;

import java.time.Duration;

/**
 * 认证域常量
 */
public final class AuthConstant {

    /**
     * 会话 cookie 名（HttpOnly，SameSite=Lax）
     */
    public static final String SESSION_COOKIE = "openagent_session";

    /**
     * cookie 会话的认证方式标识（写入 {@code RequestIdentity.authenticationMethod}）
     */
    public static final String AUTH_METHOD_COOKIE = "cookie";

    /**
     * API Key 的认证方式标识
     */
    public static final String AUTH_METHOD_API_KEY = "api_key";

    /**
     * API Key 明文前缀
     */
    public static final String API_KEY_PREFIX = "oag_";

    /**
     * 会话有效期（SQLite 单机存储，重启不失效）
     */
    public static final Duration SESSION_TTL = Duration.ofDays(7);

    private AuthConstant() {
    }
}
