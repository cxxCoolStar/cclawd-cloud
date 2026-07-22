package ai.openagent.bootstrap.channel;

import ai.openagent.agent.AgentRunResult;
import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.agentrun.AgentRunHandle;
import ai.openagent.bootstrap.channel.wechat.WechatChannelAdapter;
import ai.openagent.bootstrap.channel.wechat.WechatCredentials;
import ai.openagent.bootstrap.channel.wechat.WechatILinkClient;
import ai.openagent.bootstrap.persistence.ChannelBindingRecord;
import ai.openagent.bootstrap.persistence.ChannelConversationRecord;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Owns channel adapter lifecycles and bridges accepted messages to Agent runs. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelRuntimeManager {

    private final ChannelRepository channelRepository;
    private final AgentRunCoordinator runCoordinator;
    private final ObjectMapper objectMapper;
    private final Map<String, ImChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final ExecutorService outboundExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "channel-outbound");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void startConfiguredChannels() {
        for (ChannelBindingRecord binding : channelRepository.listEnabledBindings()) {
            try {
                start(binding);
            } catch (RuntimeException error) {
                log.warn("[channel] Could not start configured binding, channel={}, accountId={}",
                        binding.channelType(), binding.accountId(), error);
            }
        }
    }

    public synchronized void start(ChannelBindingRecord binding) {
        String key = key(binding.channelType(), binding.accountId());
        ImChannelAdapter previous = adapters.remove(key);
        if (previous != null) {
            previous.close();
        }
        if (!binding.enabled()) {
            return;
        }
        ImChannelAdapter adapter = createAdapter(binding);
        adapters.put(key, adapter);
        adapter.start(message -> accept(binding, adapter, message));
    }

    public synchronized void stop(String channel, String accountId) {
        ImChannelAdapter adapter = adapters.remove(key(channel, accountId));
        if (adapter != null) {
            adapter.close();
        }
    }

    public String status(String channel, String accountId) {
        ImChannelAdapter adapter = adapters.get(key(channel, accountId));
        return adapter == null ? "stopped" : adapter.status();
    }

    @PreDestroy
    public void close() {
        adapters.values().forEach(ImChannelAdapter::close);
        adapters.clear();
        outboundExecutor.shutdownNow();
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

    private void accept(
            ChannelBindingRecord binding, ImChannelAdapter adapter, ChannelInboundMessage message) {
        ChannelConversationRecord conversation = channelRepository.resolveConversation(
                binding, message.chatId(), message.chatterId(), message.contextToken());
        if (!channelRepository.claimInbound(binding.id(), message.messageId(), conversation.id())) {
            return;
        }
        AgentRunHandle handle;
        try {
            handle = runCoordinator.startWithResult(
                    binding.userId(), binding.agentId(), conversation.sessionId(), message.text(),
                    conversation.toScope(binding));
            channelRepository.attachRun(binding.id(), message.messageId(), handle.runId());
        } catch (RuntimeException error) {
            channelRepository.releaseInbound(binding.id(), message.messageId());
            throw error;
        }
        handle.completion().whenComplete((result, error) -> {
            if (error != null) {
                log.warn("[channel] Agent run failed before reply, runId={}", handle.runId(), error);
            } else if (!result.finalContent().isBlank()) {
                outboundExecutor.execute(() -> sendWithRetry(adapter, message, result));
            }
        });
    }

    private void sendWithRetry(
            ImChannelAdapter adapter, ChannelInboundMessage message, AgentRunResult result) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                adapter.send(message.chatId(), result.finalContent(), message.contextToken());
                return;
            } catch (RuntimeException error) {
                last = error;
                log.warn("[channel] Reply send failed, runId={}, attempt={}", result.runId(), attempt, error);
                if (!pause(attempt)) {
                    return;
                }
            }
        }
        log.error("[channel] Reply delivery exhausted, runId={}", result.runId(), last);
    }

    private boolean pause(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
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
}
