package ai.openagent.bootstrap.status;

import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PlatformStatusService {

    private final Instant startedAt = Instant.now();
    private final int port;
    private final String version;
    private final boolean registrationOpen;
    private final boolean dockerSandboxEnabled;
    private final OpenAgentStore store;
    private final ModelSettings modelSettings;

    public PlatformStatusService(
            @Value("${server.port:18953}") int port,
            @Value("${openagent.version:0.1.0-SNAPSHOT}") String version,
            @Value("${openagent.registration-open:false}") boolean registrationOpen,
            @Value("${openagent.sandbox.docker-enabled:false}") boolean dockerSandboxEnabled,
            OpenAgentStore store,
            ModelSettings modelSettings) {
        this.port = port;
        this.version = version;
        this.registrationOpen = registrationOpen;
        this.dockerSandboxEnabled = dockerSandboxEnabled;
        this.store = store;
        this.modelSettings = modelSettings;
    }

    public Map<String, Object> currentStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", true);
        status.put("registrationOpen", registrationOpen);
        status.put("running", true);
        status.put("port", port);
        status.put("mode", "local");
        status.put("version", version);
        status.put("uptime", formatDuration(Duration.between(startedAt, Instant.now())));
        status.put("agents", store.listAgents(OpenAgentStore.LOCAL_USER_ID).stream().map(this::agentStatus).toList());
        status.put("channels", new ArrayList<>());
        status.put("provider", Map.of(
                "name", modelSettings.provider(),
                "model", modelSettings.name(),
                "apiBase", modelSettings.apiBase(),
                "apiKey", modelSettings.ready() ? "configured" : ""));
        status.put("modelReady", modelSettings.ready());
        status.put("cronJobs", 0);
        status.put("plugins", 0);
        status.put("capabilities", PlatformCapabilities.v1Defaults(dockerSandboxEnabled));
        return status;
    }

    private Map<String, Object> agentStatus(AgentRecord agent) {
        return Map.of(
                "id", agent.id(),
                "name", agent.name(),
                "model", agent.model(),
                "workspace", "");
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;

        if (days > 0) {
            return "%dd %dh %dm %ds".formatted(days, hours, minutes, remainder);
        }
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, minutes, remainder);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, remainder);
        }
        return "%ds".formatted(remainder);
    }
}