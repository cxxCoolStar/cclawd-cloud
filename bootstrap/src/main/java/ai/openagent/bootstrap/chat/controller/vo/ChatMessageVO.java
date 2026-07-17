package ai.openagent.bootstrap.chat.controller.vo;

import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * 历史消息视图对象（形状对齐前端 ChatHistoryMessage：assistant 消息
 * 携带 toolCalls 供工具分组渲染，tool 消息携带 toolCallId 配对，
 * metadata 携带 iterationCapReached 等 UI 标记）
 *
 * @param role       消息角色（user / assistant / tool）
 * @param content    消息内容
 * @param toolCalls  assistant 声明的 tool calls（无则省略）
 * @param toolCallId tool 消息配对的 tool call ID（无则省略）
 * @param name       tool 消息的工具名（无则省略）
 * @param metadata   UI metadata（无则省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageVO(
        String role,
        String content,
        List<ToolCallVO> toolCalls,
        String toolCallId,
        String name,
        Map<String, Object> metadata) {

    /**
     * assistant tool call 视图（字段名与前端 ChatHistoryMessage.toolCalls 一致）
     */
    public record ToolCallVO(String id, String name, String arguments) {}

    /**
     * 由持久化记录装配
     *
     * <p>
     * assistant 的 metadata_json 内嵌 toolCallsJson（工具调用原始 JSON）
     * 与 rawAssistant（模型 wire 格式，仅供历史重放，不下发前端）；
     * 其余键作为 UI metadata 透传
     * </p>
     */
    public static ChatMessageVO from(ChatMessageRecord record, ObjectMapper objectMapper) {
        Map<String, Object> stored = parse(record.metadataJson(), objectMapper);
        List<ToolCallVO> toolCalls = null;
        Object toolCallsJson = stored.remove("toolCallsJson");
        stored.remove("rawAssistant");
        if (toolCallsJson instanceof String json && !json.isBlank()) {
            try {
                toolCalls = objectMapper.readValue(json, new TypeReference<List<ToolCallVO>>() {});
            } catch (JsonProcessingException ignored) {
                // 历史元数据损坏时降级为无工具调用的普通消息
            }
        }
        return new ChatMessageVO(
                record.role(),
                record.content(),
                toolCalls,
                blankToNull(record.toolCallId()),
                blankToNull(record.toolName()),
                stored.isEmpty() ? null : stored);
    }

    private static Map<String, Object> parse(String metadataJson, ObjectMapper objectMapper) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (JsonProcessingException ignored) {
            return new java.util.LinkedHashMap<>();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
