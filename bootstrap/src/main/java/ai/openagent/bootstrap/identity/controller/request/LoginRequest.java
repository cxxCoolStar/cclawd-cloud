package ai.openagent.bootstrap.identity.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（对齐前端 login()：{login, password}）
 *
 * @param login    用户名或邮箱
 * @param password 密码
 */
public record LoginRequest(
        @NotBlank(message = "login required") String login,
        @NotBlank(message = "password required") String password) {}
