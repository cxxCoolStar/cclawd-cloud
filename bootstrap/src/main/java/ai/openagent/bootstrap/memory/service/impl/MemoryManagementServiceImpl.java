package ai.openagent.bootstrap.memory.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.memory.MemoryService;
import ai.openagent.bootstrap.memory.controller.request.MemoryUpdateRequest;
import ai.openagent.bootstrap.memory.controller.vo.MemoryVO;
import ai.openagent.bootstrap.memory.service.MemoryManagementService;
import ai.openagent.framework.identity.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemoryManagementServiceImpl implements MemoryManagementService {

    private final MemoryService memoryService;
    private final AgentService agentService;

    @Override
    public MemoryVO get(String agentId) {
        agentService.requireAccess(agentId);
        return load(agentId);
    }

    @Override
    public MemoryVO update(String agentId, MemoryUpdateRequest request) {
        agentService.requireAccess(agentId);
        String userId = RequestContext.requireUserId();
        if (request.memory() != null) {
            memoryService.saveMemory(userId, agentId, request.memory());
        }
        if (request.user() != null) {
            memoryService.saveUserFile(userId, agentId, request.user());
        }
        return load(agentId);
    }

    private MemoryVO load(String agentId) {
        return new MemoryVO(memoryService.loadMemory(agentId), memoryService.loadUserFile(agentId));
    }
}