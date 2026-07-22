package ai.openagent.bootstrap.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.openagent.agent.AgentRunResult;
import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelInboxRecord;
import ai.openagent.bootstrap.persistence.ChannelInboxWorkItem;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChannelAgentWorkerTest {

    private static final String INBOX_ID = "inbox-1";
    private static final String RUN_ID = "run-1";

    private final ChannelMessageBus messageBus = mock(ChannelMessageBus.class);
    private final ChannelMessageRepository messageRepository = mock(ChannelMessageRepository.class);
    private final ChannelDispatchRepository dispatchRepository = mock(ChannelDispatchRepository.class);
    private final AgentRunCoordinator runCoordinator = mock(AgentRunCoordinator.class);
    private final ChannelAgentWorker worker = new ChannelAgentWorker(
            messageBus, messageRepository, dispatchRepository, runCoordinator);

    private ChannelInboxWorkItem work;

    @BeforeEach
    void setUp() {
        ChannelInboxRecord inbox = new ChannelInboxRecord(
                INBOX_ID, "binding-1", "conversation-1", "external-1", 1L,
                "question", "context", ChannelMessageStatus.PROCESSING, 1, 0L,
                "worker-1", 1L, RUN_ID, "", 1L, null, 1L, 1L);
        ChannelInboundMessage message = new ChannelInboundMessage(
                "wechat", "account-1", "chat-1", "chatter-1", "external-1",
                "question", "context");
        work = new ChannelInboxWorkItem(inbox, null, null, message);
        when(messageRepository.findNextInboundId("conversation-1")).thenReturn(Optional.empty());
    }

    @Test
    void completesInboundWhenRunCompletedWithReply() {
        AgentRunResult result = result(AgentRunStatus.COMPLETED, "answer", "", "");
        when(messageRepository.completeInbound(any(), eq(RUN_ID), eq("chat-1"), eq("answer"), eq("context")))
                .thenReturn(Optional.empty());

        worker.handleRunCompletion(work, RUN_ID, result, null);

        verify(messageRepository).completeInbound(
                work.inbox(), RUN_ID, "chat-1", "answer", "context");
        verify(messageRepository, never()).interruptInbound(any(), any());
    }

    @Test
    void completesInboundWhenLimitReachedWithFinalReply() {
        AgentRunResult result = result(AgentRunStatus.LIMIT_REACHED, "summary", "", "");
        when(messageRepository.completeInbound(any(), eq(RUN_ID), eq("chat-1"), eq("summary"), eq("context")))
                .thenReturn(Optional.empty());

        worker.handleRunCompletion(work, RUN_ID, result, null);

        verify(messageRepository).completeInbound(
                work.inbox(), RUN_ID, "chat-1", "summary", "context");
        verify(messageRepository, never()).interruptInbound(any(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentRunStatus.class,
            names = {"FAILED", "TIMED_OUT", "INTERRUPTED"})
    void interruptsInboundWhenRunCannotProduceReply(AgentRunStatus status) {
        AgentRunResult result = result(
                status, "", "RUN_FAILED", "UnresolvedAddressException");

        worker.handleRunCompletion(work, RUN_ID, result, null);

        verify(messageRepository).interruptInbound(INBOX_ID, "UnresolvedAddressException");
        verify(messageRepository, never()).completeInbound(any(), any(), any(), any(), any());
    }

    @Test
    void interruptsInboundWhenSuccessfulRunHasNoReply() {
        AgentRunResult result = result(AgentRunStatus.COMPLETED, "", "", "");

        worker.handleRunCompletion(work, RUN_ID, result, null);

        verify(messageRepository).interruptInbound(
                INBOX_ID, "Agent run COMPLETED produced no outbound reply");
        verify(messageRepository, never()).completeInbound(any(), any(), any(), any(), any());
    }

    @Test
    void interruptsInboundWhenCompletionFutureFails() {
        RuntimeException error = new RuntimeException("completion failed");

        worker.handleRunCompletion(work, RUN_ID, null, error);

        verify(messageRepository).interruptInbound(INBOX_ID, "completion failed");
        verify(messageRepository, never()).completeInbound(any(), any(), any(), any(), any());
    }

    private static AgentRunResult result(
            AgentRunStatus status, String finalContent, String errorCode, String errorMessage) {
        return new AgentRunResult(RUN_ID, status, finalContent, 0, errorCode, errorMessage);
    }
}
