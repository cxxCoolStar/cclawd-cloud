package ai.openagent.agent.context;

import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ToolCall;
import java.util.List;

/**
 * token 估算器
 *
 * <p>
 * 粗略估算：chars/4，content 与 tool call 的 name/arguments 均计入。
 * 不引入 tokenizer 依赖，估算偏保守，阈值本身也是经验值
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
