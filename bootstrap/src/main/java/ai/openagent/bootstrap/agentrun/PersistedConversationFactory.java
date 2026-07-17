package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentConversation;
import ai.openagent.agent.AgentConversationFactory;
import ai.openagent.agent.AgentRunCommand;
import ai.openagent.agent.context.ContextCompactor;
import ai.openagent.agent.context.ConversationSummarizer;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.memory.MemoryService;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.infra.ai.LLMService;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话上下文实现（AgentConversation 端口的持久化装配）
 *
 * <p>
 * open 时装载 agent/provider 配置与完整历史；运行期在内存中演进
 * 消息列表，assistant/tool 消息随写随持久化。持久化格式：
 * assistant 的 tool_calls 与 rawAssistant 存入 metadata_json（重启后
 * 历史重放仍能配对与命中缓存），tool 消息经 tool_call_id/tool_name
 * 列配对（V2 方案 9.4）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersistedConversationFactory implements AgentConversationFactory {

    private final AgentRepository agentRepository;
    private final ProviderRepository providerRepository;
    private final ChatSessionRepository sessionRepository;
    private final ToolProperties toolProperties;
    private final AgentProperties agentProperties;
    private final LLMService llmService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    @Override
    public AgentConversation open(AgentRunCommand command) {
        AgentRecord agent = agentRepository.findById(command.agentId())
                .orElseThrow(() -> new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
        ProviderRecord provider = providerRepository.findById(agent.providerId())
                .orElseThrow(() -> new ServiceException(
                        "provider not found", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR));
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ServiceException(
                    "OPENAGENT_MODEL_API_KEY is not configured", BaseErrorCode.SERVICE_UNAVAILABLE_ERROR);
        }
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system(buildSystemPrompt(agent)));
        for (ChatMessageRecord record :
                sessionRepository.listMessages(command.userId(), command.agentId(), command.sessionId())) {
            toModelMessage(record).ifPresent(messages::add);
        }
        Path agentHome = memoryService.agentHome(command.agentId());
        Path workspace = agentHome.resolve("sessions").resolve(command.sessionId());
        ContextCompactor compactor = new ContextCompactor(
                agentProperties.contextTokenThreshold(), agentProperties.contextPruneTurnAge());
        return new PersistedConversation(command, agent, provider, messages, agentHome, workspace, compactor);
    }

    /**
     * system prompt = agent 提示词 + 记忆段落注入（V3 M2，对齐 fastclaw
     * context.go BuildSystemPromptAs：MEMORY.md/USER.md 随每轮请求注入；
     * 记忆关闭或文件为空时不拼接）
     */
    private String buildSystemPrompt(AgentRecord agent) {
        String base = agent.systemPrompt();
        if (!memoryService.enabled()) {
            return base;
        }
        StringBuilder prompt = new StringBuilder(base);
        String memory = memoryService.loadMemory(agent.id()).trim();
        if (!memory.isEmpty()) {
            prompt.append("\n\n# Long-term Memory\n").append(memory);
        }
        String userProfile = memoryService.loadUserFile(agent.id()).trim();
        if (!userProfile.isEmpty()) {
            prompt.append("\n\n# User Profile\n").append(userProfile);
        }
        return prompt.toString();
    }

    /**
     * 持久化记录 → 模型消息（未知 role 丢弃并告警）
     */
    private java.util.Optional<ModelMessage> toModelMessage(ChatMessageRecord record) {
        return switch (record.role()) {
            case "user" -> java.util.Optional.of(ModelMessage.user(record.content()));
            case "assistant" -> java.util.Optional.of(new ModelMessage(
                    ModelMessage.Role.ASSISTANT,
                    record.content(),
                    parseToolCalls(record.metadataJson()),
                    "",
                    parseRawAssistant(record.metadataJson())));
            case "tool" -> java.util.Optional.of(ModelMessage.tool(record.toolCallId(), record.content()));
            default -> {
                log.warn("[agentrun] 历史中出现未知 role={}，已跳过", record.role());
                yield java.util.Optional.empty();
            }
        };
    }

    private List<ToolCall> parseToolCalls(String metadataJson) {
        Map<String, Object> metadata = parseMetadata(metadataJson);
        Object raw = metadata.get("toolCallsJson");
        if (!(raw instanceof String json) || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCall>>() {});
        } catch (JsonProcessingException error) {
            log.warn("[agentrun] 历史 tool_calls 解析失败，按无处理", error);
            return List.of();
        }
    }

    private String parseRawAssistant(String metadataJson) {
        Object raw = parseMetadata(metadataJson).get("rawAssistant");
        return raw instanceof String json ? json : "";
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    /**
     * 单次运行的会话上下文（运行在单线程内执行，无并发访问）
     */
    private final class PersistedConversation implements AgentConversation {

        private final AgentRunCommand command;
        private final AgentRecord agent;
        private final ProviderRecord provider;
        private final List<ModelMessage> messages;
        private final Path agentHome;
        private final Path workspace;
        private final ContextCompactor compactor;

        private PersistedConversation(
                AgentRunCommand command,
                AgentRecord agent,
                ProviderRecord provider,
                List<ModelMessage> messages,
                Path agentHome,
                Path workspace,
                ContextCompactor compactor) {
            this.command = command;
            this.agent = agent;
            this.provider = provider;
            this.messages = messages;
            this.agentHome = agentHome;
            this.workspace = workspace;
            this.compactor = compactor;
        }

        @Override
        public ModelRequest buildRequest(List<ToolDefinition> tools, List<ModelMessage> transientNotes) {
            // V3 M1 上下文压缩：只作用于发给模型的上下文，不改写
            // session_messages 持久化历史（V3 方案 4.1 关键决策）；
            // 压缩对本次运行后续轮次生效（内存主列表同步替换）
            if (compactor.needsCompaction(messages)) {
                writeHistoryLogQuietly();
                List<ModelMessage> compacted = compactor.compact(messages, summarizer());
                messages.clear();
                messages.addAll(compacted);
            }
            List<ModelMessage> requestMessages = new ArrayList<>(messages);
            requestMessages.addAll(transientNotes);
            return new ModelRequest(
                    new ModelProviderConfig(provider.type(), provider.apiBase(), provider.apiKey()),
                    agent.model(),
                    requestMessages,
                    tools,
                    provider.temperature(),
                    provider.maxTokens());
        }

        /**
         * 压缩总结调用（fastclaw prov.Chat(summaryPrompt, nil, model, 2048, 0.3)）
         */
        private ConversationSummarizer summarizer() {
            return conversationText -> {
                ModelResponse response = llmService.stream(
                        new ModelRequest(
                                new ModelProviderConfig(provider.type(), provider.apiBase(), provider.apiKey()),
                                agent.model(),
                                List.of(
                                        ModelMessage.system(ContextCompactor.SUMMARIZER_SYSTEM_PROMPT),
                                        ModelMessage.user("Summarize this conversation:\n\n" + conversationText)),
                                List.of(),
                                0.3,
                                agentProperties.contextSummaryMaxTokens()),
                        event -> {});
                return response instanceof ModelResponse.Text text
                        ? text.content()
                        : ((ModelResponse.ToolCalls) response).content();
            };
        }

        /**
         * 压缩前把完整历史写入 {agentHome}/memory/logs/history_*.jsonl
         * （fastclaw writeHistoryLog）；写盘失败只告警不阻断
         */
        private void writeHistoryLogQuietly() {
            try {
                Path logDir = agentHome.resolve("memory").resolve("logs");
                Files.createDirectories(logDir);
                Path logFile = logDir.resolve("history_"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        + ".jsonl");
                try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
                    for (ModelMessage message : messages) {
                        writer.write(objectMapper.writeValueAsString(message));
                        writer.newLine();
                    }
                }
                log.info("[agentrun] 压缩前历史已落盘，file={}, messages={}", logFile, messages.size());
            } catch (IOException | RuntimeException error) {
                log.warn("[agentrun] 历史落盘失败，继续压缩", error);
            }
        }

        @Override
        public void appendAssistant(
                String content, List<ToolCall> toolCalls, String rawAssistantJson, Map<String, Object> metadata) {
            messages.add(new ModelMessage(
                    ModelMessage.Role.ASSISTANT, content, toolCalls, "", rawAssistantJson));
            sessionRepository.appendMessage(
                    command.userId(),
                    command.agentId(),
                    command.sessionId(),
                    "assistant",
                    content,
                    provider.type(),
                    agent.model(),
                    "",
                    "",
                    encodeAssistantMetadata(toolCalls, rawAssistantJson, metadata));
        }

        @Override
        public void appendToolResult(ToolCall call, ToolResult result) {
            messages.add(ModelMessage.tool(call.id(), result.observation()));
            sessionRepository.appendMessage(
                    command.userId(),
                    command.agentId(),
                    command.sessionId(),
                    "tool",
                    result.observation(),
                    provider.type(),
                    agent.model(),
                    call.id(),
                    call.name(),
                    "");
        }

        @Override
        public Path workspace() {
            return workspace;
        }

        private String encodeAssistantMetadata(
                List<ToolCall> toolCalls, String rawAssistantJson, Map<String, Object> metadata) {
            Map<String, Object> merged = new LinkedHashMap<>(metadata);
            try {
                if (!toolCalls.isEmpty()) {
                    merged.put("toolCallsJson", objectMapper.writeValueAsString(toolCalls));
                }
                if (!rawAssistantJson.isBlank()) {
                    merged.put("rawAssistant", rawAssistantJson);
                }
                return merged.isEmpty() ? "" : objectMapper.writeValueAsString(merged);
            } catch (JsonProcessingException error) {
                throw new ServiceException(
                        "could not encode assistant metadata", error, BaseErrorCode.SERVICE_ERROR);
            }
        }
    }
}
