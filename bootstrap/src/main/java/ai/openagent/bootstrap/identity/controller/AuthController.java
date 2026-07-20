package ai.openagent.bootstrap.identity.controller;

import ai.openagent.bootstrap.identity.AuthConstant;
import ai.openagent.bootstrap.identity.AuthCookies;
import ai.openagent.bootstrap.identity.controller.request.LoginRequest;
import ai.openagent.bootstrap.identity.controller.request.RegisterRequest;
import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.AuthService;
import ai.openagent.bootstrap.identity.service.IssuedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 *
 * <p>
 * 处理用户注册、登录和登出。登录成功后写入 HttpOnly SameSite=Lax 会话 cookie，
 * 前端通过 cookie 自动保持会话，无需手动携带 token
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册（受 registration-open 门控；首个用户自动成为 super_admin）。
     * 成功后自动登录：写会话 cookie
     */
    @PostMapping("/api/register")
    public CurrentUserVO register(@RequestBody @Valid RegisterRequest request, HttpServletResponse response) {
        IssuedSession session = authService.register(
                request.username(), request.email(), request.password(), request.displayName());
        AuthCookies.writeSessionCookie(response, session.token(), AuthConstant.SESSION_TTL.toSeconds());
        return session.user();
    }

    /**
     * 登录（login 为用户名或邮箱）；成功写会话 cookie
     */
    @PostMapping("/api/login")
    public CurrentUserVO login(@RequestBody @Valid LoginRequest request, HttpServletResponse response) {
        IssuedSession session = authService.login(request.login(), request.password());
        AuthCookies.writeSessionCookie(response, session.token(), AuthConstant.SESSION_TTL.toSeconds());
        return session.user();
    }

    /**
     * 登出：删会话行 + 清 cookie（幂等）
     */
    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthCookies.readSessionCookie(request);
        if (token != null) {
            authService.logout(token);
        }
        AuthCookies.writeSessionCookie(response, "", 0);
        return Map.of("ok", true);
    }
}
