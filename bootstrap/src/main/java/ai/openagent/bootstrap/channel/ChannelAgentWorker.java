package ai.openagent.bootstrap.channel;

import ai.openagent.agent.AgentRunResult;
import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.agentrun.AgentRunHandle;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelInboxRecord;
import ai.openagent.bootstrap.persistence.ChannelInboxWorkItem;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Claims durable channel inbox rows and starts their Agent runs. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${openagent.channel.roles:api,channel-ingress,agent-worker,channel-egress}'.contains('agent-worker')")
public class ChannelAgentWorker {

    private static final Duration CLAIM_TTL = Duration.ofMinutes(12);

    private final ChannelMessageBus messageBus;
    private final ChannelMessageRepository messageRepository;
    private final ChannelDispatchRepository dispatchRepository;
    private final AgentRunCoordinator runCoordinator;
    private final String consumerId = "agent-worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService consumer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-agent-worker");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[channel-trace] Agent worker started, consumerId={}", consumerId);
            consumer.execute(this::consume);
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        consumer.shutdownNow();
        log.info("[channel-trace] Agent worker stopped, consumerId={}", consumerId);
    }

    private void consume() {
        while (running.get()) {
            try {
                execute(messageBus.takeInbound());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                log.error("[channel] Inbound worker failed", error);
            }
        }
    }

    private void execute(ChannelDelivery<ChannelInboundTask> delivery) {
        ChannelInboundTask task = delivery.task();
        log.info(
                "[channel-trace] Inbound notification received, inboxId={}, consumerId={}",
                task.inboxId(), consumerId);
        long now = System.currentTimeMillis();
        Optional<ChannelInboxWorkItem> claimed = messageRepository.claimInbound(
                task.inboxId(), consumerId, now + CLAIM_TTL.toMillis(), now);
        if (claimed.isEmpty()) {
            dispatchRepository.deferInbound(task.inboxId());
            delivery.acknowledge();
            log.info(
                    "[channel-trace] Inbound claim deferred, inboxId={}, consumerId={}",
                    task.inboxId(), consumerId);
            return;
        }

        ChannelInboxWorkItem work = claimed.get();
        delivery.acknowledge();
        log.info(
                "[channel-trace] Inbound claimed, inboxId={}, bindingId={}, conversationId={}, sequenceNo={}, attempt={}, consumerId={}",
                work.inbox().id(), work.inbox().bindingId(), work.inbox().conversationId(),
                work.inbox().sequenceNo(), work.inbox().attempts(), consumerId);
        AgentRunHandle handle;
        try {
            handle = runCoordinator.startWithResult(
                    work.binding().userId(),
                    work.binding().agentId(),
                    work.conversation().sessionId(),
                    work.message().text(),
                    work.conversation().toScope(work.binding()));
            messageRepository.attachRun(
                    work.inbox().id(), handle.runId(), now + CLAIM_TTL.toMillis());
            log.info(
                    "[channel-trace] Agent run attached, inboxId={}, runId={}, agentId={}",
                    work.inbox().id(), handle.runId(), work.binding().agentId());
        } catch (RuntimeException error) {
            messageRepository.retryInbound(
                    work.inbox().id(), rootMessage(error), System.currentTimeMillis() + 1000L);
            log.warn(
                    "[channel-trace] Agent run start failed, inboxId={}, retry=true",
                    work.inbox().id(), error);
            throw error;
        }
        handle.completion().whenComplete(
                (result, error) -> handleRunCompletion(work, handle.runId(), result, error));
    }

    void handleRunCompletion(
            ChannelInboxWorkItem work, String runId, AgentRunResult result, Throwable error) {
        if (error != null) {
            log.warn(
                    "[channel] Agent run failed before reply, inboxId={}, runId={}",
                    work.inbox().id(), runId, error);
            messageRepository.interruptInbound(work.inbox().id(), rootMessage(error));
        } else if (!hasDeliverableReply(result)) {
            String reason = terminalFailure(result);
            log.warn(
                    "[channel-trace] Agent run not deliverable, inboxId={}, runId={}, status={}, errorCode={}, reason={}",
                    work.inbox().id(), result.runId(), result.status(), result.errorCode(), reason);
            messageRepository.interruptInbound(work.inbox().id(), reason);
        } else {
            log.info(
                    "[channel-trace] Agent run completed, inboxId={}, runId={}, status={}, replyLength={}",
                    work.inbox().id(), result.runId(), result.status(), length(result.finalContent()));
            messageRepository.completeInbound(
                            work.inbox(), result.runId(), work.message().chatId(),
                            result.finalContent(), work.message().contextToken())
                    .ifPresent(outbox -> {
                        log.info(
                                "[channel-trace] Outbound persisted, inboxId={}, outboxId={}, runId={}, sequenceNo={}, textLength={}",
                                work.inbox().id(), outbox.id(), outbox.runId(),
                                outbox.sequenceNo(), length(outbox.text()));
                        publishOutbound(outbox.id());
                    });
        }
        publishNext(work.inbox());
    }

    private void publishOutbound(String outboxId) {
        messageBus.publishOutbound(new ChannelOutboundTask(outboxId));
        dispatchRepository.markOutboundPublished(outboxId, System.currentTimeMillis());
        log.info("[channel-trace] Outbound published, outboxId={}", outboxId);
    }

    private void publishNext(ChannelInboxRecord inbox) {
        messageRepository.findNextInboundId(inbox.conversationId()).ifPresent(nextId -> {
            messageBus.publishInbound(new ChannelInboundTask(nextId));
            dispatchRepository.markInboundPublished(nextId, System.currentTimeMillis());
            log.info(
                    "[channel-trace] Next inbound published, completedInboxId={}, nextInboxId={}, conversationId={}",
                    inbox.id(), nextId, inbox.conversationId());
        });
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean hasDeliverableReply(AgentRunResult result) {
        return (result.status() == AgentRunStatus.COMPLETED
                        || result.status() == AgentRunStatus.LIMIT_REACHED)
                && !result.finalContent().isBlank();
    }

    private static String terminalFailure(AgentRunResult result) {
        if (!result.errorMessage().isBlank()) {
            return result.errorMessage();
        }
        if (!result.errorCode().isBlank()) {
            return result.errorCode();
        }
        return "Agent run " + result.status() + " produced no outbound reply";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
