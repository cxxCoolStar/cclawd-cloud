package ai.openagent.bootstrap.identity;

import ai.openagent.bootstrap.identity.service.ApiKeyService;
import ai.openagent.bootstrap.identity.service.AuthService;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证过滤器
 *
 * <p>
 * 保护 {@code /api/**}：优先从会话 cookie 解析 token → 查 auth_sessions
 * （过期惰性剔除）；无 cookie 时回落 {@code Authorization: Bearer <key>}
 * 按 API Key 认证（命中时身份携带 key 绑定的 agent 子集）。用户写入
 * {@link RequestContext}（请求结束清理）。未认证请求访问受保护端点返回
 * 401（标准 JSON 错误响应，前端以 ok=false 识别）。白名单：登录/注册/
 * onboard/status 及非 /api 路径（静态资源、前端页面、健康探针）直接放行
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    /**
     * 无需认证的 API 端点（精确匹配）
     */
    private static final Set<String> PUBLIC_API_PATHS =
            Set.of("/api/login", "/api/register", "/api/onboard", "/api/status");

    private final AuthService authService;
    private final ApiKeyService apiKeyService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PUBLIC_API_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = AuthCookies.readSessionCookie(request);
        Optional<RequestIdentity> identity =
                token == null ? Optional.empty() : authService.authenticate(token);
        if (identity.isEmpty()) {
            identity = readBearer(request).flatMap(apiKeyService::authenticate);
        }
        if (identity.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        RequestContext.set(identity.get());
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 读取 Bearer 凭证；未携带或非 Bearer 方案返回空
     */
    private static Optional<String> readBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
