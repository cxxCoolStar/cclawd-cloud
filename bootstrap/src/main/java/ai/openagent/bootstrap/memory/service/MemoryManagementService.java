package ai.openagent.bootstrap.memory.service;

import ai.openagent.bootstrap.memory.controller.request.MemoryUpdateRequest;
import ai.openagent.bootstrap.memory.controller.vo.MemoryVO;

public interface MemoryManagementService {

    MemoryVO get(String agentId);

    MemoryVO update(String agentId, MemoryUpdateRequest request);
}