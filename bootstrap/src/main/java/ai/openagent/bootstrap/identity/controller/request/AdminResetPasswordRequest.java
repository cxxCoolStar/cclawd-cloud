package ai.openagent.bootstrap.identity.controller.request;

/**
 * 管理面重置密码请求（POST /api/users/{id}/password）
 */
public record AdminResetPasswordRequest(String password) {}
