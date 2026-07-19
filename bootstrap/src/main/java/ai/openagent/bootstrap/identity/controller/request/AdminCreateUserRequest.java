package ai.openagent.bootstrap.identity.controller.request;

/**
 * 管理面创建用户请求（POST /api/users）
 */
public record AdminCreateUserRequest(
        String username, String email, String password, String displayName, String role, Integer agentQuota) {}
