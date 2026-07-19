package ai.openagent.bootstrap.identity.controller.request;

import java.util.List;

/**
 * 创建 API Key 请求（POST /api/apikeys）
 *
 * <p>
 * type 对齐前端契约（admin/user/agent）：仅 agent 语义落地（绑定 agentIds
 * 子集），其余按不限制处理
 * </p>
 */
public record CreateApiKeyRequest(String name, String type, List<String> agentIds) {}
