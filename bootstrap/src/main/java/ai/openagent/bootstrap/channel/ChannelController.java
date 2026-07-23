package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.controller.vo.ChannelListVO;
import ai.openagent.bootstrap.channel.service.ChannelBindingService;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import com.fasterxml.jackson.databind.JsonNode;
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

@RestController
@RequestMapping("/api/agents/{agentId}/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelBindingService channelBindingService;

    @GetMapping
    public Result<ChannelListVO> list(@PathVariable String agentId) {
        return Results.success(channelBindingService.list(agentId));
    }

    @PostMapping("/wechat/login")
    public WechatLoginService.LoginStart startWechatLogin(@PathVariable String agentId) {
        return channelBindingService.startWechatLogin(agentId);
    }

    @GetMapping("/wechat/login/status")
    public WechatLoginService.LoginStatus pollWechatLogin(
            @PathVariable String agentId, @RequestParam("session") String sessionId) {
        return channelBindingService.pollWechatLogin(agentId, sessionId);
    }

    @DeleteMapping("/{type}/{accountId}")
    public Result<Void> disconnect(
            @PathVariable String agentId, @PathVariable String type, @PathVariable String accountId) {
        channelBindingService.disconnect(agentId, type, accountId);
        return Results.success();
    }

    @PatchMapping("/{type}/{accountId}")
    public Result<Void> update(
            @PathVariable String agentId,
            @PathVariable String type,
            @PathVariable String accountId,
            @RequestBody JsonNode body) {
        channelBindingService.update(agentId, type, accountId, body);
        return Results.success();
    }
}