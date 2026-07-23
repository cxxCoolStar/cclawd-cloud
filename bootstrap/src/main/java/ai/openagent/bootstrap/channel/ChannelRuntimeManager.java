package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.config.ChannelProperties;
import ai.openagent.bootstrap.channel.wechat.WechatChannelAdapter;
import ai.openagent.bootstrap.channel.wechat.WechatCredentials;
import ai.openagent.bootstrap.channel.wechat.WechatILinkClient;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Owns channel adapters, with one renewable ingress lease per binding. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelRuntimeManager implements ChannelSenderRegistry {

    private final ChannelRepository channelRepository;
    private final ChannelIngressService ingressService;
    private final ChannelLeaseService leaseService;
    private final ChannelRuntimeRegistry runtimeRegistry;
    private final ChannelProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, ManagedAdapter> adapters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaseScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "channel-lease-manager");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void startConfiguredChannels() {
        reconcileSafely();
        if (properties.hasRole("channel-ingress")) {
            long interval = properties.lease().renewInterval().toMillis();
            leaseScheduler.scheduleWithFixedDelay(this::reconcileSafely, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void start(ChannelBindingRecord binding) {
        remove(binding.channelType(), binding.accountId());
        if (!binding.enabled() || !properties.hasRole("channel-ingress")) {
            return;
        }
        ManagedAdapter managed = new ManagedAdapter(binding, createAdapter(binding));
        adapters.put(key(binding.channelType(), binding.accountId()), managed);
        if (properties.hasRole("channel-ingress")) {
            acquireAndStart(managed);
        }
    }

    public synchronized void stop(String channel, String accountId) {
        remove(channel, accountId);
    }

    public String status(String channel, String accountId) {
        ManagedAdapter managed = adapters.get(key(channel, accountId));
        if (managed == null) {
            return "stopped";
        }
        return managed.receiverActive ? managed.adapter.status() : "standby";
    }

    @Override
    public Optional<ChannelSender> findSender(String channel, String accountId) {
        ManagedAdapter managed = adapters.get(key(channel, accountId));
        if (managed != null) {
            return Optional.of(managed.adapter);
        }
        return channelRepository.findEnabledBinding(channel, accountId).map(binding -> {
            synchronized (this) {
                ManagedAdapter current = adapters.get(key(channel, accountId));
                if (current != null) {
                    return current.adapter;
                }
                ManagedAdapter created = new ManagedAdapter(binding, createAdapter(binding));
                adapters.put(key(channel, accountId), created);
                return created.adapter;
            }
        });
    }

    @PreDestroy
    public synchronized void close() {
        leaseScheduler.shutdownNow();
        adapters.values().forEach(this::close);
        adapters.clear();
    }

    private synchronized void reconcile() {
        Set<String> enabled = new HashSet<>();
        for (ChannelBindingRecord binding : channelRepository.listEnabledBindings()) {
            String key = key(binding.channelType(), binding.accountId());
            enabled.add(key);
            if (!properties.hasRole("channel-ingress")) {
                continue;
            }
            ManagedAdapter managed = adapters.get(key);
            if (managed == null) {
                managed = new ManagedAdapter(binding, createAdapter(binding));
                adapters.put(key, managed);
            }
            if (managed.receiverActive) {
                if (!leaseService.renew(binding.id())) {
                    log.info("[channel] Binding lease lost, channel={}, accountId={}",
                            binding.channelType(), binding.accountId());
                    replaceWithStandby(key, managed);
                } else {
                    reportRuntime(managed);
                }
            } else {
                acquireAndStart(managed);
            }
        }
        adapters.entrySet().removeIf(entry -> {
            if (enabled.contains(entry.getKey())) {
                return false;
            }
            close(entry.getValue());
            return true;
        });
    }

    private void reconcileSafely() {
        try {
            reconcile();
        } catch (RuntimeException error) {
            log.warn("[channel] Binding lease reconciliation failed", error);
            synchronized (this) {
                adapters.replaceAll((key, managed) -> managed.receiverActive
                        ? replaceWithStandby(key, managed)
                        : managed);
            }
        }
    }

    private void acquireAndStart(ManagedAdapter managed) {
        if (!leaseService.acquire(managed.binding.id())) {
            return;
        }
        try {
            managed.adapter.start(message -> {
                recordMessage(managed.binding.id());
                ingressService.accept(managed.binding, message);
            });
            managed.receiverActive = true;
            reportRuntime(managed);
            log.info("[channel] Binding lease acquired, channel={}, accountId={}",
                    managed.binding.channelType(), managed.binding.accountId());
        } catch (RuntimeException error) {
            leaseService.release(managed.binding.id());
            throw error;
        }
    }

    private ManagedAdapter replaceWithStandby(String key, ManagedAdapter managed) {
        managed.adapter.close();
        managed.receiverActive = false;
        removeRuntime(managed.binding.id());
        ManagedAdapter standby = new ManagedAdapter(managed.binding, createAdapter(managed.binding));
        adapters.put(key, standby);
        return standby;
    }

    private void remove(String channel, String accountId) {
        ManagedAdapter managed = adapters.remove(key(channel, accountId));
        if (managed != null) {
            close(managed);
        }
    }

    private void close(ManagedAdapter managed) {
        managed.adapter.close();
        if (managed.receiverActive) {
            removeRuntime(managed.binding.id());
            leaseService.release(managed.binding.id());
            managed.receiverActive = false;
        }
    }

    private void reportRuntime(ManagedAdapter managed) {
        try {
            runtimeRegistry.report(managed.binding.id(), managed.adapter.status(), "");
        } catch (RuntimeException error) {
            log.warn("[channel] Runtime heartbeat update failed, bindingId={}", managed.binding.id(), error);
        }
    }

    private void recordMessage(String bindingId) {
        try {
            runtimeRegistry.recordMessage(bindingId, System.currentTimeMillis());
        } catch (RuntimeException error) {
            log.warn("[channel] Runtime message timestamp update failed, bindingId={}", bindingId, error);
        }
    }

    private void removeRuntime(String bindingId) {
        try {
            runtimeRegistry.remove(bindingId);
        } catch (RuntimeException error) {
            log.warn("[channel] Runtime heartbeat cleanup failed, bindingId={}", bindingId, error);
        }
    }
    private ImChannelAdapter createAdapter(ChannelBindingRecord binding) {
        if (!"wechat".equals(binding.channelType())) {
            throw new IllegalArgumentException("Unsupported channel: " + binding.channelType());
        }
        JsonNode data = parse(binding.credentialsJson());
        WechatCredentials credentials = new WechatCredentials(
                data.path("botToken").asText(""),
                binding.accountId(),
                data.path("baseUrl").asText(""),
                data.path("ilinkUserId").asText(""));
        String cursor = parse(binding.stateJson()).path("cursor").asText("");
        return new WechatChannelAdapter(
                credentials,
                new WechatILinkClient(objectMapper, credentials),
                cursor,
                value -> channelRepository.updateState(binding.id(), stateJson(value)));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid channel configuration JSON", error);
        }
    }

    private String stateJson(String cursor) {
        try {
            return objectMapper.writeValueAsString(Map.of("cursor", cursor));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not encode channel state", error);
        }
    }

    private static String key(String channel, String accountId) {
        return channel + ":" + accountId;
    }

    private static final class ManagedAdapter {
        private final ChannelBindingRecord binding;
        private final ImChannelAdapter adapter;
        private boolean receiverActive;

        private ManagedAdapter(ChannelBindingRecord binding, ImChannelAdapter adapter) {
            this.binding = binding;
            this.adapter = adapter;
        }
    }
}
