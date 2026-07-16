package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AgentController {

    private final OpenAgentStore store;

    public AgentController(OpenAgentStore store) {
        this.store = store;
    }

    @GetMapping("/api/agents")
    public Map<String, Object> listAgents() {
        List<Map<String, Object>> agents = store.listAgents(OpenAgentStore.LOCAL_USER_ID).stream()
                .map(this::toResponse)
                .toList();
        return Map.of("agents", agents);
    }

    @GetMapping("/api/agents/{id}")
    public Map<String, Object> getAgent(@PathVariable String id) {
        AgentRecord agent = store.findAgent(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found"));
        return Map.of("agent", toResponse(agent));
    }

    private Map<String, Object> toResponse(AgentRecord agent) {
        return Map.ofEntries(
                Map.entry("id", agent.id()),
                Map.entry("name", agent.name()),
                Map.entry("description", agent.description()),
                Map.entry("userId", agent.userId()),
                Map.entry("role", "owner"),
                Map.entry("model", agent.model()),
                Map.entry("workspace", ""),
                Map.entry("avatarUrl", "/api/agents/" + agent.id() + "/files/avatar.png"),
                Map.entry("createdAt", agent.createdAt()),
                Map.entry("isPublic", false),
                Map.entry("shareModelConfig", false));
    }
}
