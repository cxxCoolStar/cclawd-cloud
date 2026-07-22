package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Streams notification bus backed by durable PostgreSQL record IDs. */
@Slf4j
@Component
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "redis")
public class RedisChannelMessageBus implements ChannelMessageBus {

    private static final String RECORD_ID = "recordId";

    private final StringRedisTemplate redis;
    private final ChannelProperties properties;
    private final String consumerId = "channel-" + UUID.randomUUID();
    private final Lane inbound;
    private final Lane outbound;

    public RedisChannelMessageBus(StringRedisTemplate redis, ChannelProperties properties) {
        this.redis = redis;
        this.properties = properties;
        String prefix = properties.redis().keyPrefix();
        this.inbound = new Lane(prefix + ":inbound", prefix + ":inbound-workers", prefix + ":inbound:dlq");
        this.outbound = new Lane(prefix + ":outbound", prefix + ":outbound-workers", prefix + ":outbound:dlq");
    }

    @PostConstruct
    void initialize() {
        createGroup(inbound);
        createGroup(outbound);
    }

    @Override
    public void publishInbound(ChannelInboundTask task) {
        publish(inbound, task.inboxId());
    }

    @Override
    public ChannelDelivery<ChannelInboundTask> takeInbound() throws InterruptedException {
        Delivery delivery = take(inbound);
        return new ChannelDelivery<>(new ChannelInboundTask(delivery.recordId()), delivery.acknowledge());
    }

    @Override
    public void publishOutbound(ChannelOutboundTask task) {
        publish(outbound, task.outboxId());
    }

    @Override
    public ChannelDelivery<ChannelOutboundTask> takeOutbound() throws InterruptedException {
        Delivery delivery = take(outbound);
        return new ChannelDelivery<>(new ChannelOutboundTask(delivery.recordId()), delivery.acknowledge());
    }

    private void publish(Lane lane, String recordId) {
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(RECORD_ID, recordId)).withStreamKey(lane.stream()));
    }

    private Delivery take(Lane lane) throws InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            MapRecord<String, Object, Object> reclaimed = reclaim(lane);
            if (reclaimed != null) {
                Delivery delivery = decode(lane, reclaimed);
                if (delivery != null) {
                    return delivery;
                }
            }
            List<MapRecord<String, Object, Object>> records = streams().read(
                    Consumer.from(lane.group(), consumerId),
                    StreamReadOptions.empty().count(1).block(properties.redis().blockTimeout()),
                    StreamOffset.create(lane.stream(), ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                continue;
            }
            Delivery delivery = decode(lane, records.get(0));
            if (delivery != null) {
                return delivery;
            }
        }
    }

    private MapRecord<String, Object, Object> reclaim(Lane lane) {
        PendingMessages pending = streams().pending(
                lane.stream(), lane.group(), Range.unbounded(), properties.redis().reclaimBatchSize());
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.redis().pendingIdle()) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = streams().claim(
                    lane.stream(), lane.group(), consumerId, properties.redis().pendingIdle(), message.getId());
            if (!claimed.isEmpty()) {
                return claimed.get(0);
            }
        }
        return null;
    }

    private Delivery decode(Lane lane, MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get(RECORD_ID);
        String recordId = value == null ? "" : value.toString();
        if (recordId.isBlank()) {
            deadLetter(lane, record, "missing recordId");
            acknowledge(lane, record.getId());
            return null;
        }
        return new Delivery(recordId, () -> acknowledge(lane, record.getId()));
    }

    private void deadLetter(Lane lane, MapRecord<String, Object, Object> record, String reason) {
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                        "sourceStream", lane.stream(),
                        "sourceId", record.getId().getValue(),
                        "reason", reason,
                        "failedAt", Long.toString(System.currentTimeMillis())))
                .withStreamKey(lane.deadLetterStream()));
        log.warn("[channel] Redis notification moved to DLQ, stream={}, id={}, reason={}",
                lane.stream(), record.getId(), reason);
    }

    private void acknowledge(Lane lane, RecordId recordId) {
        streams().acknowledge(lane.stream(), lane.group(), recordId);
    }

    private StreamOperations<String, Object, Object> streams() {
        return redis.opsForStream();
    }

    private void createGroup(Lane lane) {
        try {
            redis.execute(connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"), bytes(lane.stream()), bytes(lane.group()), bytes("0-0"), bytes("MKSTREAM")),
                    true);
        } catch (DataAccessException error) {
            if (!rootMessage(error).contains("BUSYGROUP")) {
                throw error;
            }
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Lane(String stream, String group, String deadLetterStream) {}

    private record Delivery(String recordId, Runnable acknowledge) {}
}
