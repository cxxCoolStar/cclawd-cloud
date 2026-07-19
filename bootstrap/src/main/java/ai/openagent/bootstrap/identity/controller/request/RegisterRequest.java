package ai.openagent.bootstrap.identity.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求（对齐前端 RegisterRequest：username/email/password/displayName?）
 *
 * @param username    用户名
 * @param email       邮箱
 * @param password    密码（长度校验在服务层，与前端 min 8 一致）
 * @param displayName 显示名（缺省回退用户名）
 */
public record RegisterRequest(
        @NotBlank(message = "username required") String username,
        @NotBlank(message = "email required") String email,
        @NotBlank(message = "password required") String password,
        String displayName) {}
