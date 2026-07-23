package ai.openagent.bootstrap.channel.service;

import ai.openagent.bootstrap.channel.controller.vo.ChannelListVO;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import com.fasterxml.jackson.databind.JsonNode;

public interface ChannelBindingService {

    ChannelListVO list(String agentId);

    WechatLoginService.LoginStart startWechatLogin(String agentId);

    WechatLoginService.LoginStatus pollWechatLogin(String agentId, String sessionId);

    void disconnect(String agentId, String type, String accountId);

    void update(String agentId, String type, String accountId, JsonNode body);
}