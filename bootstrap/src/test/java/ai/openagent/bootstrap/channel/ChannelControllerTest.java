package ai.openagent.bootstrap.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.channel.controller.vo.AgentChannelVO;
import ai.openagent.bootstrap.channel.wechat.WechatLoginService;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelControllerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ChannelRepository channelRepository = mock(ChannelRepository.class);
    private final ChannelRuntimeManager runtimeManager = mock(ChannelRuntimeManager.class);
    private final WechatLoginService wechatLoginService = mock(WechatLoginService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChannelController controller;

    @BeforeEach
    void setUp() {
        controller = new ChannelController(
                agentService, channelRepository, runtimeManager, wechatLoginService);
    }

    @Test
    void listUsesAuthorizedOwnerAndMasksCredentials() {
        AgentRecord agent = agent();
        ChannelBindingRecord binding = binding();
        when(agentService.requireAccess("agent-1")).thenReturn(agent);
        when(channelRepository.listBindings("owner-1", "agent-1")).thenReturn(List.of(binding));
        when(runtimeManager.status("wechat", "account-1")).thenReturn("connected");

        var response = controller.list("agent-1");

        assertEquals("0", response.getCode());
        assertEquals(1, response.getData().channels().size());
        AgentChannelVO channel = response.getData().channels().get(0);
        assertEquals("********", channel.botToken());
        assertEquals("connected", channel.status());
    }

    @Test
    void updateAndDeleteStayWithinAuthorizedBinding() {
        AgentRecord agent = agent();
        ChannelBindingRecord binding = binding();
        when(agentService.requireAccess("agent-1")).thenReturn(agent);
        when(channelRepository.updateSharedIdentity(
                "owner-1", "agent-1", "wechat", "account-1", true)).thenReturn(true);
        when(channelRepository.findOwnedBinding(
                "owner-1", "agent-1", "wechat", "account-1"))
                .thenReturn(java.util.Optional.of(binding));
        when(channelRepository.deleteBinding(
                "owner-1", "agent-1", "wechat", "account-1")).thenReturn(true);

        assertEquals("0", controller.update(
                "agent-1", "wechat", "account-1",
                objectMapper.createObjectNode().put("sharedIdentity", true)).getCode());
        assertEquals("0", controller.disconnect(
                "agent-1", "wechat", "account-1").getCode());

        verify(runtimeManager).start(binding);
        verify(runtimeManager).stop("wechat", "account-1");
    }

    @Test
    void deniedAgentAccessStopsBeforeChannelStateIsReadOrChanged() {
        when(agentService.requireAccess("agent-1")).thenThrow(new ClientException(
                "agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));

        assertThrows(ClientException.class, () -> controller.list("agent-1"));
        assertThrows(ClientException.class, () -> controller.update(
                "agent-1", "wechat", "account-1",
                objectMapper.createObjectNode().put("sharedIdentity", true)));
        assertThrows(ClientException.class,
                () -> controller.disconnect("agent-1", "wechat", "account-1"));

        verifyNoInteractions(channelRepository, runtimeManager, wechatLoginService);
    }

    @Test
    void missingOwnedBindingDoesNotStopAnotherAdapter() {
        when(agentService.requireAccess("agent-1")).thenReturn(agent());
        when(channelRepository.deleteBinding(
                "owner-1", "agent-1", "wechat", "account-1")).thenReturn(false);

        assertThrows(ClientException.class,
                () -> controller.disconnect("agent-1", "wechat", "account-1"));

        verifyNoInteractions(runtimeManager, wechatLoginService);
    }

    @Test
    void updateRejectsMissingSharedIdentity() {
        when(agentService.requireAccess("agent-1")).thenReturn(agent());

        assertThrows(ClientException.class, () -> controller.update(
                "agent-1", "wechat", "account-1", objectMapper.createObjectNode()));
        verifyNoInteractions(channelRepository, runtimeManager, wechatLoginService);
    }

    private static AgentRecord agent() {
        return new AgentRecord(
                "agent-1", "owner-1", "Agent", "", "provider", "model", "prompt", 1L, 1L);
    }

    private static ChannelBindingRecord binding() {
        return new ChannelBindingRecord(
                "binding-1", "owner-1", "agent-1", "wechat", "account-1",
                "WeChat", "{\"botToken\":\"secret\"}", true, false, "{}", 1L, 1L);
    }
}
