package ai.openagent.framework.identity;

import java.util.Objects;
import java.util.Set;

/**
 * 请求身份
 *
 * @param userId               认证用户 ID
 * @param effectiveUserId      生效用户 ID（actAs 等场景的代理身份，缺省同 userId）
 * @param ownerUserId          资源属主用户 ID（缺省同 effectiveUserId）
 * @param role                 角色（super_admin / user）
 * @param authenticationMethod 认证方式（cookie / api_key 等）
 * @param allowedAgentIds      API Key 绑定的 agent 子集；空集 = 不限制（cookie
 *                             会话与不绑定子集的 key 均为空集）
 */
public record RequestIdentity(
        String userId,
        String effectiveUserId,
        String ownerUserId,
        String role,
        String authenticationMethod,
        Set<String> allowedAgentIds) {

    /**
     * 兼容构造：无 agent 子集限制（cookie 会话等）
     */
    public RequestIdentity(
            String userId, String effectiveUserId, String ownerUserId, String role, String authenticationMethod) {
        this(userId, effectiveUserId, ownerUserId, role, authenticationMethod, Set.of());
    }

    public RequestIdentity {
        userId = Objects.requireNonNullElse(userId, "");
        effectiveUserId = Objects.requireNonNullElse(effectiveUserId, userId);
        ownerUserId = Objects.requireNonNullElse(ownerUserId, effectiveUserId);
        role = Objects.requireNonNullElse(role, "anonymous");
        authenticationMethod = Objects.requireNonNullElse(authenticationMethod, "none");
        allowedAgentIds = allowedAgentIds == null ? Set.of() : Set.copyOf(allowedAgentIds);
    }

    public boolean isAuthenticated() {
        return !userId.isBlank();
    }

    public boolean isPlatformAdmin() {
        return "super_admin".equals(role);
    }
}
