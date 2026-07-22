package ai.openagent.bootstrap.memory;

import ai.openagent.agent.AgentConversationScope;

import ai.openagent.bootstrap.memory.config.MemoryProperties;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 自动记忆提取服务（V3 方案 M2）
 *
 * <p>
 * 触发规则：记忆总开关与 auto-persist 开关均开启时，统计 (agent, user) 的
 * 用户消息总数，每 N 条触发一次提取（用户消息数 % 间隔数 == 0）。
 * 提取过程：取当前会话最近 20 条消息（跳过 system），让 LLM 输出 JSON
 * {"memory_facts": [...], "user_notes": [...]}，追加到 MEMORY.md / USER.md。
 * 所有失败均 WARN 后跳过，不影响运行终态。
 * </p>
 */
@Slf4j
@Service
public class AutoPersistMemoryService {

    private static final DateTimeFormatter AUTO_PERSIST_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int LOOKBACK_MESSAGES = 20;
    private static final int MEMORY_CONTEXT_CHARS = 500;
    private static final int MESSAGE_PREVIEW_CHARS = 300;

    private final MemoryProperties memoryProperties;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentRepository agentRepository;
    private final ProviderRepository providerRepository;
    private final LLMService llmService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public AutoPersistMemoryService(
            MemoryProperties memoryProperties,
            ChatSessionRepository chatSessionRepository,
            AgentRepository agentRepository,
            ProviderRepository providerRepository,
            LLMService llmService,
            MemoryService memoryService,
            ObjectMapper objectMapper) {
        this.memoryProperties = memoryProperties;
        this.chatSessionRepository = chatSessionRepository;
        this.agentRepository = agentRepository;
        this.providerRepository = providerRepository;
        this.llmService = llmService;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * 完成一次运行后调用；内部判定是否触发 LLM 提取
     */
    public void maybePersist(String userId, String agentId, String sessionId) {
        maybePersist(userId, agentId, sessionId, null);
    }

    public void maybePersist(
            String userId, String agentId, String sessionId, AgentConversationScope scope) {
        if (!memoryProperties.enabled() || !memoryProperties.autoPersistEnabled()) {
            return;
        }
        int interval = memoryProperties.autoPersistInterval();
        if (interval <= 0) {
            return;
        }
        long count = scope == null
                ? chatSessionRepository.countUserMessages(userId, agentId)
                : chatSessionRepository.countUserMessages(userId, agentId, sessionId);
        boolean willFire = count > 0 && count % interval == 0;
        log.info("[memory] auto-persist gate, agentId={}, userId={}, count={}, interval={}, willFire={}",
                agentId, userId, count, interval, willFire);
        if (!willFire) {
            return;
        }
        try {
            AgentRecord agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new ServiceException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
            ProviderRecord provider = providerRepository.findById(agent.providerId())
                    .orElseThrow(() -> new ServiceException("provider not found", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR));
            if (provider.apiKey() == null || provider.apiKey().isBlank()) {
                log.warn("[memory] auto-persist 跳过：未配置模型 API key");
                return;
            }
            List<ChatMessageRecord> history = chatSessionRepository.listMessages(userId, agentId, sessionId);
            List<ChatMessageRecord> recent = history;
            if (recent.size() > LOOKBACK_MESSAGES) {
                recent = recent.subList(recent.size() - LOOKBACK_MESSAGES, recent.size());
            }
            List<String> facts = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            if (!recent.isEmpty()) {
                Map<String, List<String>> extracted = extractFacts(provider, agentId, agent.model(), recent, scope);
                facts.addAll(extracted.getOrDefault("memory_facts", List.of()));
                notes.addAll(extracted.getOrDefault("user_notes", List.of()));
            }
            appendFacts(agentId, MemoryService.MEMORY_FILE, facts,
                    () -> memoryService.loadMemory(agentId, scope),
                    (u, c) -> memoryService.saveMemory(u, agentId, scope, c),
                    userId);
            appendFacts(agentId, MemoryService.USER_FILE, notes,
                    () -> memoryService.loadUserFile(agentId, scope),
                    (u, c) -> memoryService.saveUserFile(u, agentId, scope, c),
                    userId);
            log.info("[memory] auto-persist 完成，agentId={}, facts={}, notes={}", agentId, facts.size(), notes.size());
        } catch (RuntimeException error) {
            log.warn("[memory] auto-persist 失败，不影响运行终态", error);
        }
    }

    private Map<String, List<String>> extractFacts(
            ProviderRecord provider,
            String agentId,
            String model,
            List<ChatMessageRecord> recent,
            AgentConversationScope scope) {
        StringBuilder conversation = new StringBuilder();
        for (ChatMessageRecord record : recent) {
            if ("system".equals(record.role())) {
                continue;
            }
            String content = record.content();
            if (content.length() > MESSAGE_PREVIEW_CHARS) {
                content = content.substring(0, MESSAGE_PREVIEW_CHARS) + "...";
            }
            conversation.append("[").append(record.role()).append("]: ").append(content).append("\n");
        }
        String currentMemory = truncate(memoryService.loadMemory(agentId, scope), MEMORY_CONTEXT_CHARS);
        String currentUser = truncate(memoryService.loadUserFile(agentId, scope), MEMORY_CONTEXT_CHARS);
        String prompt = """
                Analyze this conversation and extract:
                1. Key facts, decisions, or learnings worth remembering (for MEMORY.md)
                2. User preferences, profile details, or work style notes (for USER.md)

                Current MEMORY.md:
                %s

                Current USER.md:
                %s

                Recent conversation:
                %s

                Output JSON only (no markdown fences):
                {"memory_facts": ["fact1", "fact2"], "user_notes": ["note1"]}
                If nothing worth saving, output: {"memory_facts": [], "user_notes": []}
                """.formatted(currentMemory, currentUser, conversation);
        ModelResponse response = llmService.stream(
                new ModelRequest(
                        new ModelProviderConfig(provider.type(), provider.apiBase(), provider.apiKey()),
                        model,
                        List.of(ModelMessage.user(prompt)),
                        List.of(),
                        0.3,
                        // kimi-k2.5 等 reasoning 模型会先消耗 reasoning 预算，
                        // 200 tokens 会导致正文为空（model returned an empty response），
                        // 实测 2000 可稳定产出
                        2000),
                event -> {});
        String json = response instanceof ModelResponse.Text text
                ? text.content()
                : ((ModelResponse.ToolCalls) response).content();
        json = stripJSONFence(json);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            log.warn("[memory] auto-persist JSON 解析失败，model 响应: {}", truncate(json, 200));
            return Map.of();
        }
    }

    private void appendFacts(
            String agentId,
            String file,
            List<String> items,
            java.util.function.Supplier<String> loader,
            java.util.function.BiConsumer<String, String> saver,
            String userId) {
        if (items.isEmpty()) {
            return;
        }
        String current = loader.get().trim();
        StringBuilder sb = new StringBuilder(current);
        if (!current.isEmpty() && !current.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("\n## Auto-persisted: ").append(LocalDateTime.now().format(AUTO_PERSIST_TS)).append("\n");
        for (String item : items) {
            sb.append("- ").append(item).append("\n");
        }
        saver.accept(userId, sb.toString());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 移除模型在 JSON 外包裹的 ```json 围栏
     */
    static String stripJSONFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            trimmed = trimmed.substring(newline + 1);
        } else {
            trimmed = trimmed.substring(3);
        }
        trimmed = trimmed.strip();
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).strip();
        }
        return trimmed;
    }
}
