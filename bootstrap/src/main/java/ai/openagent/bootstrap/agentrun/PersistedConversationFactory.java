package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentConversation;
import ai.openagent.agent.AgentConversationFactory;
import ai.openagent.agent.AgentRunCommand;
import ai.openagent.agent.tool.ToolResult;
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
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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
        messages.add(ModelMessage.system(agent.systemPrompt()));
        for (ChatMessageRecord record :
                sessionRepository.listMessages(command.userId(), command.agentId(), command.sessionId())) {
            toModelMessage(record).ifPresent(messages::add);
        }
        Path workspace = Path.of(toolProperties.workspaceRoot())
                .resolve(command.agentId())
                .resolve("sessions")
                .resolve(command.sessionId());
        return new PersistedConversation(command, agent, provider, messages, workspace);
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
        private final Path workspace;

        private PersistedConversation(
                AgentRunCommand command,
                AgentRecord agent,
                ProviderRecord provider,
                List<ModelMessage> messages,
                Path workspace) {
            this.command = command;
            this.agent = agent;
            this.provider = provider;
            this.messages = messages;
            this.workspace = workspace;
        }

        @Override
        public ModelRequest buildRequest(List<ToolDefinition> tools, List<ModelMessage> transientNotes) {
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
