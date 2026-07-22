package ai.openagent.bootstrap.channel;

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
            consumer.execute(this::consume);
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        consumer.shutdownNow();
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
        long now = System.currentTimeMillis();
        Optional<ChannelInboxWorkItem> claimed = messageRepository.claimInbound(
                task.inboxId(), consumerId, now + CLAIM_TTL.toMillis(), now);
        if (claimed.isEmpty()) {
            dispatchRepository.deferInbound(task.inboxId());
            delivery.acknowledge();
            return;
        }

        ChannelInboxWorkItem work = claimed.get();
        delivery.acknowledge();
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
        } catch (RuntimeException error) {
            messageRepository.retryInbound(
                    work.inbox().id(), rootMessage(error), System.currentTimeMillis() + 1000L);
            throw error;
        }
        handle.completion().whenComplete((result, error) -> {
            if (error != null) {
                log.warn("[channel] Agent run failed before reply, runId={}", handle.runId(), error);
                messageRepository.interruptInbound(work.inbox().id(), rootMessage(error));
            } else {
                messageRepository.completeInbound(
                                work.inbox(), result.runId(), work.message().chatId(),
                                result.finalContent(), work.message().contextToken())
                        .ifPresent(outbox -> publishOutbound(outbox.id()));
            }
            publishNext(work.inbox());
        });
    }

    private void publishOutbound(String outboxId) {
        messageBus.publishOutbound(new ChannelOutboundTask(outboxId));
        dispatchRepository.markOutboundPublished(outboxId, System.currentTimeMillis());
    }

    private void publishNext(ChannelInboxRecord inbox) {
        messageRepository.findNextInboundId(inbox.conversationId()).ifPresent(nextId -> {
            messageBus.publishInbound(new ChannelInboundTask(nextId));
            dispatchRepository.markInboundPublished(nextId, System.currentTimeMillis());
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
