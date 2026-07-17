package ai.openagent.agent.context;

import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ToolCall;
import java.util.List;

/**
 * token 估算器（对齐 fastclaw internal/agent/compaction.go EstimateTokens）
 *
 * <p>
 * 粗略估算：chars/4，content 与 tool call 的 name/arguments 均计入。
 * 不引入 tokenizer 依赖，估算误差与 fastclaw 同向（偏保守即可，
 * 阈值本身也是经验值）
 * </p>
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimateTokens(List<ModelMessage> messages) {
        int total = 0;
        for (ModelMessage message : messages) {
            total += message.content().length() / 4;
            for (ToolCall toolCall : message.toolCalls()) {
                total += toolCall.arguments().length() / 4;
                total += toolCall.name().length() / 4;
            }
        }
        return total;
    }
}
