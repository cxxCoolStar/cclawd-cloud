package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.persistence.OpenAgentStore;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 智能体服务实现
 */
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final OpenAgentStore store;

    @Override
    public List<AgentVO> listAgents() {
        return store.listAgents(OpenAgentStore.LOCAL_USER_ID).stream()
                .map(AgentVO::from)
                .toList();
    }

    @Override
    public AgentVO getAgent(String id) {
        return store.findAgent(id)
                .map(AgentVO::from)
                .orElseThrow(() -> new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
    }
}
