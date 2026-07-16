package ai.openagent.agent;

import ai.openagent.framework.identity.RequestIdentity;
import java.util.Map;
import java.util.Objects;

public record AgentRunCommand(
        String runId,
        RequestIdentity identity,
        String agentId,
        String sessionKey,
        String input,
        Map<String, Object> effectiveConfig) {

    public AgentRunCommand {
        runId = Objects.requireNonNull(runId, "runId");
        identity = Objects.requireNonNull(identity, "identity");
        agentId = Objects.requireNonNull(agentId, "agentId");
        sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
        input = Objects.requireNonNull(input, "input");
        effectiveConfig = effectiveConfig == null ? Map.of() : Map.copyOf(effectiveConfig);
    }
}

