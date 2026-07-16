package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.persistence.OpenAgentStore;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalIdentityController {

    @GetMapping("/api/me")
    public Map<String, Object> me() {
        return Map.of(
                "ok", true,
                "user", Map.of(
                        "id", OpenAgentStore.LOCAL_USER_ID,
                        "username", "local",
                        "email", "local@openagent.invalid",
                        "role", "super_admin",
                        "displayName", "Local User",
                        "status", "active",
                        "agentQuota", -1),
                "authMethod", "local",
                "readOnly", false,
                "deployMode", "self-hosted");
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout() {
        return Map.of("ok", true);
    }
}
