package ai.openagent.bootstrap.memory.controller;

import ai.openagent.bootstrap.memory.controller.request.MemoryUpdateRequest;
import ai.openagent.bootstrap.memory.controller.vo.MemoryVO;
import ai.openagent.bootstrap.memory.service.MemoryManagementService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryManagementService memoryManagementService;

    @GetMapping("/api/agents/{agentId}/memory")
    public Result<MemoryVO> getMemory(@PathVariable String agentId) {
        return Results.success(memoryManagementService.get(agentId));
    }

    @PutMapping("/api/agents/{agentId}/memory")
    public Result<MemoryVO> putMemory(@PathVariable String agentId, @RequestBody MemoryUpdateRequest request) {
        return Results.success(memoryManagementService.update(agentId, request));
    }
}