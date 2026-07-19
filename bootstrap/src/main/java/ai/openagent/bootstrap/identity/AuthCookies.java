package ai.openagent.bootstrap.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * 会话 cookie 读写助手（认证过滤器与认证控制器共用）
 */
public final class AuthCookies {

    /**
     * 写会话 cookie（HttpOnly, SameSite=Lax；本地 HTTP 部署不加 Secure）
     */
    public static void writeSessionCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(AuthConstant.SESSION_COOKIE, token)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 从请求读取会话 cookie；未携带或为空返回 null
     */
    public static String readSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (AuthConstant.SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private AuthCookies() {
    }
}
