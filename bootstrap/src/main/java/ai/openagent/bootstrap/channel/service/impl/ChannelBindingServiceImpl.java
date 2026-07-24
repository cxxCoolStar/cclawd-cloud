package ai.openagent.bootstrap.channel.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.agent.service.bo.AgentBO;
import ai.openagent.bootstrap.channel.ChannelRuntimeManager;
import ai.openagent.bootstrap.channel.controller.vo.AgentChannelVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelListVO;
import ai.openagent.bootstrap.channel.service.ChannelBindingService;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChannelBindingServiceImpl implements ChannelBindingService {

    private final AgentService agentService;
    private final ChannelRepository channelRepository;
    private final ChannelRuntimeManager runtimeManager;
    private final WechatLoginService wechatLoginService;

    @Override
    public ChannelListVO list(String agentId) {
        AgentBO agent = agentService.requireAccess(agentId);
        List<AgentChannelVO> channels = channelRepository.listBindings(agent.userId(), agentId).stream()
                .map(binding -> AgentChannelVO.from(binding, runtimeManager.status(
                        binding.channelType(), binding.accountId())))
                .toList();
        return new ChannelListVO(channels);
    }

    @Override
    public WechatLoginService.LoginStart startWechatLogin(String agentId) {
        AgentBO agent = agentService.requireAccess(agentId);
        return wechatLoginService.start(agent.userId(), agentId);
    }

    @Override
    public WechatLoginService.LoginStatus pollWechatLogin(String agentId, String sessionId) {
        AgentBO agent = agentService.requireAccess(agentId);
        return wechatLoginService.poll(agent.userId(), agentId, sessionId);
    }

    @Override
    public void disconnect(String agentId, String type, String accountId) {
        AgentBO agent = agentService.requireAccess(agentId);
        if (!channelRepository.deleteBinding(agent.userId(), agentId, type, accountId)) {
            throw new ClientException("channel not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        runtimeManager.stop(type, accountId);
    }

    @Override
    public void update(String agentId, String type, String accountId, JsonNode body) {
        if (!body.has("sharedIdentity") || !body.get("sharedIdentity").isBoolean()) {
            throw new ClientException("sharedIdentity boolean required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        AgentBO agent = agentService.requireAccess(agentId);
        if (!channelRepository.updateSharedIdentity(
                agent.userId(), agentId, type, accountId, body.get("sharedIdentity").asBoolean())) {
            throw new ClientException("channel not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        channelRepository.findOwnedBinding(agent.userId(), agentId, type, accountId)
                .ifPresent(runtimeManager::start);
    }
}
