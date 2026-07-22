package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.persistence.ChannelDispatchRepository;
import ai.openagent.bootstrap.persistence.ChannelMessageRepository;
import ai.openagent.bootstrap.persistence.ChannelStaleDispatchRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Republishes durable work and recovers expired worker claims. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelRecoveryScheduler {

    private static final int BATCH_SIZE = 100;
    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final Duration STALE_PUBLICATION = Duration.ofSeconds(30);

    private final ChannelMessageRepository messageRepository;
    private final ChannelDispatchRepository dispatchRepository;
    private final ChannelStaleDispatchRepository staleDispatchRepository;
    private final ChannelMessageBus messageBus;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-recovery");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::recoverSafely,
                INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    private void recoverSafely() {
        try {
            recover();
        } catch (RuntimeException error) {
            log.warn("[channel] Recovery scan failed", error);
        }
    }

    private void recover() {
        long now = System.currentTimeMillis();
        for (String id : messageRepository.listExpiredInboundClaimIds(now, BATCH_SIZE)) {
            messageRepository.recoverExpiredInbound(id, now);
        }
        for (String id : messageRepository.listExpiredOutboundClaimIds(now, BATCH_SIZE)) {
            messageRepository.recoverExpiredOutbound(id, now);
        }
        for (String id : messageRepository.listPublishableInboundIds(now, BATCH_SIZE)) {
            messageBus.publishInbound(new ChannelInboundTask(id));
            dispatchRepository.markInboundPublished(id, now);
        }
        for (String id : messageRepository.listPublishableOutboundIds(now, BATCH_SIZE)) {
            messageBus.publishOutbound(new ChannelOutboundTask(id));
            dispatchRepository.markOutboundPublished(id, now);
        }
        long publishedBefore = now - STALE_PUBLICATION.toMillis();
        for (String id : staleDispatchRepository.listStaleInboundIds(publishedBefore, BATCH_SIZE)) {
            messageBus.publishInbound(new ChannelInboundTask(id));
            staleDispatchRepository.touchInbound(id, now);
        }
        for (String id : staleDispatchRepository.listStaleOutboundIds(publishedBefore, BATCH_SIZE)) {
            messageBus.publishOutbound(new ChannelOutboundTask(id));
            staleDispatchRepository.touchOutbound(id, now);
        }
    }
}
