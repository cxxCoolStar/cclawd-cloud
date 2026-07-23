package ai.openagent.bootstrap.agent.controller;

import ai.openagent.bootstrap.agent.controller.request.AgentCreateRequest;
import ai.openagent.bootstrap.agent.controller.request.AgentUpdateRequest;
import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentDetailVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentListVO;
import ai.openagent.bootstrap.agent.service.AgentManagementService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentManagementService agentManagementService;

    @GetMapping("/api/agents")
    public Result<AgentListVO> listAgents() {
        return Results.success(agentManagementService.list());
    }

    @GetMapping("/api/agents/{id}")
    public Result<AgentDetailVO> getAgent(@PathVariable String id) {
        return Results.success(agentManagementService.get(id));
    }

    @PostMapping("/api/agents")
    public ResponseEntity<Result<AgentDetailVO>> createAgent(@RequestBody @Valid AgentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Results.success(agentManagementService.create(request)));
    }

    @DeleteMapping("/api/agents/{id}")
    public Result<Void> deleteAgent(@PathVariable String id) {
        agentManagementService.delete(id);
        return Results.success();
    }

    @GetMapping("/api/agents/{id}/config")
    public AgentConfigVO getAgentConfig(@PathVariable String id) {
        return agentManagementService.config(id);
    }

    @PutMapping("/api/agents/{id}")
    public AgentConfigVO updateAgent(@PathVariable String id, @RequestBody @Valid AgentUpdateRequest request) {
        return agentManagementService.update(id, request);
    }
}