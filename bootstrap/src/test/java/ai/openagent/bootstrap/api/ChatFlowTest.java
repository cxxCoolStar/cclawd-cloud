package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.SessionEventRecord;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.bootstrap.persistence.UserRepository;
import ai.openagent.framework.identity.RequestIdentity;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelEvent;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/chat-flow-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
@AutoConfigureMockMvc
@Import(ChatFlowTest.FakeModelConfiguration.class)
class ChatFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRunCoordinator runCoordinator;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Test
    void streamsAndPersistsACompleteChatTurn() throws Exception {
        String sessionId = "test-session-" + UUID.randomUUID();

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("local-user"));

        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].id").value("default"))
                .andExpect(jsonPath("$.agents[0].model").value("test-model"));

        MvcResult pending = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"default","sessionId":"%s","message":"Hello"}
                                """.formatted(sessionId)))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 推模式下回合在独立线程池执行，emitter 收到 done 事件后才 complete
        // ——等待异步结果就绪再 dispatch，避免与回合线程竞态
        pending.getAsyncResult(5_000);

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("\"type\":\"content_delta\"")))
                .andExpect(content().string(containsString("Hello from OpenAgent")))
                .andExpect(content().string(containsString("\"type\":\"done\"")));

        mockMvc.perform(get("/api/chat/history")
                        .param("agentId", "default")
                        .param("sessionId", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].role").value("user"))
                .andExpect(jsonPath("$.history[0].content").value("Hello"))
                .andExpect(jsonPath("$.history[1].role").value("assistant"))
                .andExpect(jsonPath("$.history[1].content").value("Hello from OpenAgent"))
                .andExpect(jsonPath("$.latestEventSeq").value(2));

        mockMvc.perform(get("/api/chat/sessions").param("agentId", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value(sessionId));
    }

    @Test
    void persistsCompletedTurnWithoutAnySubscriber() throws Exception {
        String sessionId = "disconnected-session-" + UUID.randomUUID();

        // 不建立任何 SSE 订阅直接开启回合，等价于客户端在回合开始后立刻
        // 断开——回合必须照常完成并落库（fastclaw detached-context 语义）
        turnCoordinatorStart(sessionId, "Stay running");

        var messages = sessionRepository.listMessages(IdentityConstant.LOCAL_USER_ID, "default", sessionId);
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
        assertEquals("Hello from OpenAgent", messages.get(1).content());

        var events = sessionRepository.listEventsSince(IdentityConstant.LOCAL_USER_ID, "default", sessionId, -1);
        assertEquals(2, events.size());
        assertEquals("content", events.get(0).eventType());
        assertEquals("done", events.get(1).eventType());
    }

    @Test
    void asyncRunPersistsUnderIdentitySnapshottedAtSubmit() throws Exception {
        // 异步路径身份快照（V9 M1）：提交线程的 ThreadLocal 在 start 返回后
        // 即被清理，执行线程落库的消息/事件/run 必须归属提交时快照的用户
        String userId = "flow-user-" + UUID.randomUUID().toString().substring(0, 8);
        userRepository.insert(new UserRecord(
                userId, userId, userId + "@test.invalid", "user", "Flow User", "active",
                "", "", -1, System.currentTimeMillis()));
        String sessionId = "snapshot-session-" + UUID.randomUUID();

        TestIdentity.callAs(new RequestIdentity(userId, null, null, "user", "test"),
                () -> runCoordinator.start("default", sessionId, "who am i").get(5, TimeUnit.SECONDS));

        var messages = sessionRepository.listMessages(userId, "default", sessionId);
        assertEquals(2, messages.size());
        var events = sessionRepository.listEventsSince(userId, "default", sessionId, -1);
        assertEquals(List.of("content", "done"),
                events.stream().map(SessionEventRecord::eventType).toList());
        var runs = runRepository.listBySession(userId, "default", sessionId, 10);
        assertEquals(1, runs.size());
        assertEquals(userId, runs.get(0).userId());
    }

    private void turnCoordinatorStart(String sessionId, String message) throws Exception {
        TestIdentity.callAs(TestIdentity.localUser(),
                () -> runCoordinator.start("default", sessionId, message).get(5, TimeUnit.SECONDS));
    }

    @TestConfiguration
    static class FakeModelConfiguration {

        @Bean
        @Primary
        LLMService fakeLlmService() {
            return (request, listener) -> {
                listener.onEvent(new ModelEvent.TextDelta("Hello "));
                listener.onEvent(new ModelEvent.TextDelta("from OpenAgent"));
                return new ModelResponse.Text("Hello from OpenAgent", TokenUsage.ZERO, "");
            };
        }
    }
}
