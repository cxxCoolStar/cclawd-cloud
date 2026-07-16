package ai.openagent.agent;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String runId,
        AgentRunState state,
        String type,
        Map<String, Object> payload,
        Instant occurredAt) {

    public AgentEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}

