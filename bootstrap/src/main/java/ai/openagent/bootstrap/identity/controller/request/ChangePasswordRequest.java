package ai.openagent.bootstrap.identity.controller.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改密码请求（对齐前端 changeMyPassword()：{oldPassword, newPassword}）
 *
 * @param oldPassword 旧密码
 * @param newPassword 新密码（长度校验在服务层）
 */
public record ChangePasswordRequest(
        @NotBlank(message = "oldPassword required") String oldPassword,
        @NotBlank(message = "newPassword required") String newPassword) {}
