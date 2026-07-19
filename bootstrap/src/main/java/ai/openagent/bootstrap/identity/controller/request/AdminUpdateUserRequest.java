package ai.openagent.bootstrap.identity.controller.request;

/**
 * 管理面更新用户请求（PUT /api/users/{id}；null 字段不动）
 */
public record AdminUpdateUserRequest(String displayName, String role, String status, Integer agentQuota) {}
