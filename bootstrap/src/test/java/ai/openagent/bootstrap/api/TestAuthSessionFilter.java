package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.identity.AuthConstant;
import ai.openagent.bootstrap.identity.AuthCookies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 测试会话注入过滤器（仅测试代码，组件扫描随测试类路径生效）
 *
 * <p>
 * 等价于"浏览器已登录"：为未携带会话 cookie 的 API 请求注入
 * {@link TestIdentity#TEST_SESSION_TOKEN} cookie，使存量测试无需逐个
 * 改造即可以种子 local-user 身份走真实认证链路（AuthFilter 照常查库
 * 校验）。请求已显式携带会话 cookie（如过期会话用例）、携带
 * {@code Authorization} 头（API Key 用例）或带 {@value #SKIP_HEADER}
 * 头（未认证 401 用例）时不注入
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class TestAuthSessionFilter extends OncePerRequestFilter {

    /**
     * 携带此头即跳过注入，用于构造"未登录"请求
     */
    public static final String SKIP_HEADER = "X-Test-No-Auth";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || request.getHeader(SKIP_HEADER) != null
                || request.getHeader("Authorization") != null
                || AuthCookies.readSessionCookie(request) != null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public Cookie[] getCookies() {
                return new Cookie[] {new Cookie(AuthConstant.SESSION_COOKIE, TestIdentity.TEST_SESSION_TOKEN)};
            }
        }, response);
    }
}
