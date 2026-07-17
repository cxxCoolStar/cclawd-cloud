package ai.openagent.agent.context;

import ai.openagent.infra.ai.model.ModelMessage;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 上下文压缩器（V3 方案 M1，对照 fastclaw internal/agent/compaction.go
 * CompactMessages 逐段移植）
 *
 * <p>
 * 保留的 fastclaw 行为：
 * <ul>
 *   <li>token 超过阈值才触发（默认 80000，DefaultTokenThreshold）；</li>
 *   <li>两段式：Step 1 把最近 pruneTurnAge（默认 20，PruneTurnAge）条以外
 *       的超长 tool 消息替换为占位符，保留 role 与 toolCallId——配对不断裂；
 *       Step 2 仍超阈值时调用模型把旧消息总结为一条
 *       {@code [Conversation Summary]} user 消息拼接近期尾部；</li>
 *   <li>尾部切割经 safeCompactionCutoff 修正：切割后第一条不得是
 *       role=tool（否则 OpenAI 兼容 API 拒绝："Messages with role 'tool'
 *       must be a response to a preceding message with 'tool_calls'"）；</li>
 *   <li>总结调用失败降级为仅裁剪，不让运行失败。</li>
 * </ul>
 * 与 fastclaw 的已知差异：fastclaw 总结文本只含 OriginUser 消息，
 * OpenAgent 的 ModelMessage 暂无 origin 字段，V3 对全部角色生成总结文本
 * （V3 方案 8.2 有意偏离登记）；前导 system 消息钉住不参与压缩
 * （fastclaw 的 system prompt 本就不在会话历史内，此处等价处理）
 * </p>
 */
@Slf4j
public class ContextCompactor {

    /**
     * 裁剪占位符（fastclaw truncatedPlaceholder 逐字一致）
     */
    public static final String TRUNCATED_PLACEHOLDER = "[Result truncated - see memory logs]";

    /**
     * 总结调用的固定 system prompt（fastclaw compressOlderMessages 逐字一致）
     */
    public static final String SUMMARIZER_SYSTEM_PROMPT =
            "You are a conversation summarizer. Summarize the following conversation history into a "
                    + "compact summary that preserves key facts, decisions, and context. "
                    + "Be concise but don't lose important details.";

    /**
     * 超过该长度的旧 tool 消息才被裁剪（fastclaw pruneOldToolResults 的 200）
     */
    private static final int PRUNE_CONTENT_MIN_CHARS = 200;

    private final int tokenThreshold;
    private final int pruneTurnAge;

    public ContextCompactor(int tokenThreshold, int pruneTurnAge) {
        this.tokenThreshold = tokenThreshold;
        this.pruneTurnAge = pruneTurnAge;
    }

    /**
     * 是否达到触发阈值（历史落盘等前置动作以此判断）
     */
    public boolean needsCompaction(List<ModelMessage> messages) {
        return TokenEstimator.estimateTokens(messages) >= tokenThreshold;
    }

    /**
     * 两段式压缩。返回压缩后的新列表；未触发时返回原列表（调用方可按
     * 引用判等跳过后续处理）
     */
    public List<ModelMessage> compact(List<ModelMessage> messages, ConversationSummarizer summarizer) {
        if (!needsCompaction(messages)) {
            return messages;
        }
        int tokensBefore = TokenEstimator.estimateTokens(messages);
        log.info("[compactor] 上下文压缩触发，tokens={}, threshold={}, messageCount={}",
                tokensBefore, tokenThreshold, messages.size());

        // 前导 system 消息钉住（fastclaw 的 system prompt 不在会话历史内）
        int headCount = 0;
        while (headCount < messages.size()
                && messages.get(headCount).role() == ModelMessage.Role.SYSTEM) {
            headCount++;
        }
        List<ModelMessage> head = messages.subList(0, headCount);
        List<ModelMessage> body = new ArrayList<>(messages.subList(headCount, messages.size()));

        // Step 1：裁剪旧工具结果
        List<ModelMessage> pruned = pruneOldToolResults(body);
        int prunedTokens = TokenEstimator.estimateTokens(pruned) + TokenEstimator.estimateTokens(head);
        log.info("[compactor] 裁剪完成，tokensBefore={}, tokensAfter={}", tokensBefore, prunedTokens);
        if (prunedTokens < tokenThreshold) {
            return join(head, pruned);
        }

        // Step 2：模型总结旧消息；失败降级为仅裁剪（fastclaw 同语义）
        try {
            List<ModelMessage> compressed = compressOlderMessages(pruned, summarizer);
            log.info("[compactor] 总结完成，tokensBefore={}, tokensAfter={}",
                    prunedTokens, TokenEstimator.estimateTokens(compressed));
            return join(head, compressed);
        } catch (RuntimeException error) {
            log.warn("[compactor] 总结调用失败，降级为仅裁剪", error);
            return join(head, pruned);
        }
    }

    /**
     * 裁剪：仅处理最近 pruneTurnAge 条以外的消息，超长 tool 消息替换为
     * 占位符并保留 toolCallId（配对不断裂）
     */
    List<ModelMessage> pruneOldToolResults(List<ModelMessage> messages) {
        if (messages.size() <= pruneTurnAge) {
            return messages;
        }
        int cutoff = messages.size() - pruneTurnAge;
        List<ModelMessage> result = new ArrayList<>(messages);
        for (int i = 0; i < cutoff; i++) {
            ModelMessage message = result.get(i);
            if (message.role() == ModelMessage.Role.TOOL
                    && message.content().length() > PRUNE_CONTENT_MIN_CHARS) {
                result.set(i, ModelMessage.tool(message.toolCallId(), TRUNCATED_PLACEHOLDER));
            }
        }
        return result;
    }

    /**
     * 总结：旧消息（切割点之前）生成摘要 user 消息，拼接近期尾部
     */
    private List<ModelMessage> compressOlderMessages(
            List<ModelMessage> messages, ConversationSummarizer summarizer) {
        if (messages.size() <= pruneTurnAge) {
            return messages;
        }
        int cutoff = safeCompactionCutoff(messages, messages.size() - pruneTurnAge);
        List<ModelMessage> older = messages.subList(0, cutoff);

        StringBuilder text = new StringBuilder();
        for (ModelMessage message : older) {
            text.append('[').append(message.role().wireValue()).append("] ")
                    .append(message.content()).append('\n');
        }
        String summary = summarizer.summarize(text.toString());

        List<ModelMessage> compressed = new ArrayList<>(messages.size() - cutoff + 1);
        compressed.add(ModelMessage.user("[Conversation Summary]\n" + summary));
        compressed.addAll(messages.subList(cutoff, messages.size()));
        return compressed;
    }

    /**
     * 切割点修正：前移跳过前导 tool 消息，保证尾部不以 role=tool 开头
     * （fastclaw safeCompactionCutoff 纯函数移植）
     */
    static int safeCompactionCutoff(List<ModelMessage> messages, int cutoff) {
        int adjusted = Math.max(cutoff, 0);
        while (adjusted < messages.size() && messages.get(adjusted).role() == ModelMessage.Role.TOOL) {
            adjusted++;
        }
        return adjusted;
    }

    private static List<ModelMessage> join(List<ModelMessage> head, List<ModelMessage> body) {
        if (head.isEmpty()) {
            return body;
        }
        List<ModelMessage> joined = new ArrayList<>(head.size() + body.size());
        joined.addAll(head);
        joined.addAll(body);
        return joined;
    }
}
