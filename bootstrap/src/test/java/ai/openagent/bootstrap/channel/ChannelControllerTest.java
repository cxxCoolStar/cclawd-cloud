package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.openagent.bootstrap.channel.controller.vo.AgentChannelVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelListVO;
import ai.openagent.bootstrap.channel.service.ChannelBindingService;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelControllerTest {

    private final ChannelBindingService channelBindingService = mock(ChannelBindingService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChannelController controller;

    @BeforeEach
    void setUp() {
        controller = new ChannelController(channelBindingService);
    }

    @Test
    void listDelegatesToServiceAndWrapsResult() {
        ChannelListVO channels = new ChannelListVO(List.of(new AgentChannelVO(
                "wechat", "account-1", "bot", "********", true, false, "connected", "2026-01-01T00:00:00Z")));
        when(channelBindingService.list("agent-1")).thenReturn(channels);

        var response = controller.list("agent-1");

        assertEquals("0", response.getCode());
        assertEquals(channels, response.getData());
        verify(channelBindingService).list("agent-1");
    }

    @Test
    void commandsDelegateToService() {
        var body = objectMapper.createObjectNode().put("sharedIdentity", true);

        assertEquals("0", controller.disconnect("agent-1", "wechat", "account-1").getCode());
        assertEquals("0", controller.update("agent-1", "wechat", "account-1", body).getCode());

        verify(channelBindingService).disconnect("agent-1", "wechat", "account-1");
        verify(channelBindingService).update("agent-1", "wechat", "account-1", body);
    }

    @Test
    void wechatEndpointsDelegateToService() {
        WechatLoginService.LoginStart start = mock(WechatLoginService.LoginStart.class);
        WechatLoginService.LoginStatus status = mock(WechatLoginService.LoginStatus.class);
        when(channelBindingService.startWechatLogin("agent-1")).thenReturn(start);
        when(channelBindingService.pollWechatLogin("agent-1", "session-1")).thenReturn(status);

        assertEquals(start, controller.startWechatLogin("agent-1"));
        assertEquals(status, controller.pollWechatLogin("agent-1", "session-1"));
    }
}