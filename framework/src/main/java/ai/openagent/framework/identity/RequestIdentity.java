package ai.openagent.framework.identity;

import java.util.Objects;

public record RequestIdentity(
        String userId,
        String effectiveUserId,
        String ownerUserId,
        String role,
        String authenticationMethod) {

    public RequestIdentity {
        userId = Objects.requireNonNullElse(userId, "");
        effectiveUserId = Objects.requireNonNullElse(effectiveUserId, userId);
        ownerUserId = Objects.requireNonNullElse(ownerUserId, effectiveUserId);
        role = Objects.requireNonNullElse(role, "anonymous");
        authenticationMethod = Objects.requireNonNullElse(authenticationMethod, "none");
    }

    public boolean isAuthenticated() {
        return !userId.isBlank();
    }

    public boolean isPlatformAdmin() {
        return "super_admin".equals(role);
    }
}

