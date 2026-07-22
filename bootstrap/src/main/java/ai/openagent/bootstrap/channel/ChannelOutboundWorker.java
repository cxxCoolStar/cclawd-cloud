package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.persistence.ChannelBindingLookupRepository;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import ai.openagent.bootstrap.persistence.ChannelOutboxRecord;
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

/** Claims durable channel outbox rows and delivers final replies. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${openagent.channel.roles:api,channel-ingress,agent-worker,channel-egress}'.contains('channel-egress')")
public class ChannelOutboundWorker {

    private static final Duration CLAIM_TTL = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 3;

    private final ChannelMessageBus messageBus;
    private final ChannelSenderRegistry senderRegistry;
    private final ChannelMessageRepository messageRepository;
    private final ChannelDispatchRepository dispatchRepository;
    private final ChannelBindingLookupRepository bindingRepository;
    private final String consumerId = "outbound-worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService consumer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-outbound-worker");
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
                deliver(messageBus.takeOutbound());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                log.error("[channel] Outbound worker failed", error);
            }
        }
    }

    private void deliver(ChannelDelivery<ChannelOutboundTask> delivery) {
        ChannelOutboundTask task = delivery.task();
        long now = System.currentTimeMillis();
        Optional<ChannelOutboxRecord> claimed = messageRepository.claimOutbound(
                task.outboxId(), consumerId, now + CLAIM_TTL.toMillis(), now);
        if (claimed.isEmpty()) {
            dispatchRepository.deferOutbound(task.outboxId());
            delivery.acknowledge();
            return;
        }

        ChannelOutboxRecord outbox = claimed.get();
        delivery.acknowledge();
        try {
            ChannelBindingRecord binding = bindingRepository.findById(outbox.bindingId())
                    .orElseThrow(() -> new IllegalStateException("Channel binding not found"));
            ChannelSender sender = senderRegistry.findSender(binding.channelType(), binding.accountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No channel sender registered for " + binding.channelType() + ":" + binding.accountId()));
            sender.send(outbox.chatId(), outbox.text(), outbox.contextToken());
            messageRepository.markOutboundSent(outbox.id(), "");
        } catch (RuntimeException error) {
            boolean dead = outbox.attempts() >= MAX_ATTEMPTS;
            long availableAt = System.currentTimeMillis() + 1000L * outbox.attempts();
            messageRepository.retryOutbound(outbox.id(), rootMessage(error), availableAt, dead);
            log.warn("[channel] Reply send failed, runId={}, attempt={}, dead={}",
                    outbox.runId(), outbox.attempts(), dead, error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
