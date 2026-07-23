package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.channel.controller.vo.AgentChannelVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelListVO;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.web.Results;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner-authorized management API for per-agent IM channel bindings. */
@RestController
@RequestMapping("/api/agents/{agentId}/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final AgentService agentService;
    private final ChannelRepository channelRepository;
    private final ChannelRuntimeManager runtimeManager;
    private final WechatLoginService wechatLoginService;

    @GetMapping
    public Result<ChannelListVO> list(@PathVariable String agentId) {
        AgentRecord agent = agentService.requireAccess(agentId);
        List<AgentChannelVO> channels = channelRepository.listBindings(agent.userId(), agentId).stream()
                .map(binding -> AgentChannelVO.from(binding, runtimeManager.status(
                        binding.channelType(), binding.accountId())))
                .toList();
        return Results.success(new ChannelListVO(channels));
    }

    @PostMapping("/wechat/login")
    public WechatLoginService.LoginStart startWechatLogin(@PathVariable String agentId) {
        AgentRecord agent = agentService.requireAccess(agentId);
        return wechatLoginService.start(agent.userId(), agentId);
    }

    @GetMapping("/wechat/login/status")
    public WechatLoginService.LoginStatus pollWechatLogin(
            @PathVariable String agentId, @RequestParam("session") String sessionId) {
        AgentRecord agent = agentService.requireAccess(agentId);
        return wechatLoginService.poll(agent.userId(), agentId, sessionId);
    }

    @DeleteMapping("/{type}/{accountId}")
    public Result<Void> disconnect(
            @PathVariable String agentId,
            @PathVariable String type,
            @PathVariable String accountId) {
        AgentRecord agent = agentService.requireAccess(agentId);
        boolean deleted = channelRepository.deleteBinding(agent.userId(), agentId, type, accountId);
        if (!deleted) {
            throw new ClientException("channel not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        runtimeManager.stop(type, accountId);
        return Results.success();
    }

    @PatchMapping("/{type}/{accountId}")
    public Result<Void> update(
            @PathVariable String agentId,
            @PathVariable String type,
            @PathVariable String accountId,
            @RequestBody JsonNode body) {
        AgentRecord agent = agentService.requireAccess(agentId);
        if (!body.has("sharedIdentity") || !body.get("sharedIdentity").isBoolean()) {
            throw new ClientException("sharedIdentity boolean required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        boolean updated = channelRepository.updateSharedIdentity(
                agent.userId(), agentId, type, accountId, body.get("sharedIdentity").asBoolean());
        if (!updated) {
            throw new ClientException("channel not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        channelRepository.findOwnedBinding(agent.userId(), agentId, type, accountId)
                .ifPresent(runtimeManager::start);
        return Results.success();
    }

}
