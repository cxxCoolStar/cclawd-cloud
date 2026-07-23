package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.agent.controller.request.AgentCreateRequest;
import ai.openagent.bootstrap.agent.controller.request.AgentUpdateRequest;
import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentDetailVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentListVO;

public interface AgentManagementService {

    AgentListVO list();

    AgentDetailVO get(String id);

    AgentDetailVO create(AgentCreateRequest request);

    AgentConfigVO config(String id);

    AgentConfigVO update(String id, AgentUpdateRequest request);

    void delete(String id);
}