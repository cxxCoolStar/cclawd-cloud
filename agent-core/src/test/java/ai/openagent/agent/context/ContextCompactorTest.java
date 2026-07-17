package ai.openagent.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩器单测（V3 方案 M1 行为清单，对照 fastclaw
 * compaction_test.go 风险点）
 */
class ContextCompactorTest {

    private static final String LONG_TEXT = "x".repeat(400);

    private final ContextCompactor compactor = new ContextCompactor(100, 4);

    @Test
    void estimatesTokensAsCharsPerFourIncludingToolCalls() {
        List<ModelMessage> messages = List.of(
                ModelMessage.user("12345678"),
                new ModelMessage(ModelMessage.Role.ASSISTANT, "",
                        List.of(new ToolCall("c1", "read_file", "{}".repeat(20))), "", ""),
                ModelMessage.tool("c1", "1234"));
        // user 8/4=2；tool call name 9/4=2 + arguments 40/4=10；tool 4/4=1
        assertEquals(15, TokenEstimator.estimateTokens(messages));
    }

    @Test
    void returnsSameInstanceBelowThreshold() {
        List<ModelMessage> messages = List.of(ModelMessage.user("short"));
        assertSame(messages, compactor.compact(messages, text -> "summary"));
    }

    @Test
    void prunesOnlyOldLongToolResultsAndKeepsPairing() {
        ContextCompactor compactor = new ContextCompactor(1, 2);
        List<ModelMessage> messages = new ArrayList<>(List.of(
                assistantWithCall("c1"), ModelMessage.tool("c1", LONG_TEXT),
                assistantWithCall("c2"), ModelMessage.tool("c2", LONG_TEXT),
                ModelMessage.user("recent one"),
                ModelMessage.user("recent two")));
        // 总结器若被调用则必然成功——本用例验证裁剪后仍超阈值时会走总结，
        // 因此改用"裁剪后即低于阈值"的场景：阈值设为占位符也超、原文不超的中间值不可行，
        // 直接断言 pruneOldToolResults 纯函数行为
        List<ModelMessage> pruned = compactor.pruneOldToolResults(messages);
        // cutoff = 6 - 2 = 4：前 4 条中的 tool 消息（c1、c2）被替换
        assertEquals(ContextCompactor.TRUNCATED_PLACEHOLDER, pruned.get(1).content());
        assertEquals("c1", pruned.get(1).toolCallId());
        assertEquals(ContextCompactor.TRUNCATED_PLACEHOLDER, pruned.get(3).content());
        assertEquals("c2", pruned.get(3).toolCallId());
        // 近期尾部原样保留
        assertEquals("recent one", pruned.get(4).content());
    }

    @Test
    void keepsShortToolResultsVerbatim() {
        ContextCompactor compactor = new ContextCompactor(1, 2);
        List<ModelMessage> messages = List.of(
                assistantWithCall("c1"), ModelMessage.tool("c1", "short result"),
                ModelMessage.user("a"), ModelMessage.user("b"), ModelMessage.user("c"));
        List<ModelMessage> pruned = compactor.pruneOldToolResults(messages);
        assertEquals("short result", pruned.get(1).content());
    }

    @Test
    void doesNotPruneWhenHistoryNotLongerThanTail() {
        ContextCompactor compactor = new ContextCompactor(1, 10);
        List<ModelMessage> messages = List.of(
                ModelMessage.tool("c1", LONG_TEXT), ModelMessage.user("a"));
        assertSame(messages, compactor.pruneOldToolResults(messages));
    }

    @Test
    void cutoffSkipsLeadingToolMessages() {
        // cutoff 落在 tool 消息上时必须前移，否则尾部以 role=tool 开头被 API 拒绝
        List<ModelMessage> messages = List.of(
                ModelMessage.user("old"),
                assistantWithCall("c1"), ModelMessage.tool("c1", "r1"), ModelMessage.tool("c2", "r2"),
                ModelMessage.user("tail"));
        assertEquals(4, ContextCompactor.safeCompactionCutoff(messages, 2));
        // 切割点不在 tool 上时保持不动
        assertEquals(1, ContextCompactor.safeCompactionCutoff(messages, 1));
        assertEquals(0, ContextCompactor.safeCompactionCutoff(messages, -3));
    }

    @Test
    void summarizesOlderMessagesAndKeepsTailIntact() {
        ContextCompactor compactor = new ContextCompactor(1, 2);
        List<ModelMessage> messages = List.of(
                ModelMessage.user("very old question"),
                ModelMessage.assistant("very old answer"),
                assistantWithCall("c1"), ModelMessage.tool("c1", "result"),
                ModelMessage.user("recent question"),
                ModelMessage.assistant("recent answer"));
        List<ModelMessage> compacted = compactor.compact(messages, text -> {
            assertTrue(text.contains("very old question"));
            return "summary of old turns";
        });
        // cutoff = 6 - 2 = 4，已是非 tool 边界：summary + 尾部 2 条
        assertEquals(3, compacted.size());
        assertEquals(ModelMessage.Role.USER, compacted.get(0).role());
        assertTrue(compacted.get(0).content().startsWith("[Conversation Summary]"));
        assertTrue(compacted.get(0).content().contains("summary of old turns"));
        assertEquals("recent question", compacted.get(1).content());
        assertEquals("recent answer", compacted.get(2).content());
    }

    @Test
    void tailNeverStartsWithToolAfterCompression() {
        ContextCompactor compactor = new ContextCompactor(1, 2);
        // 切割点 4-2=2 落在 tool 上 → 前移；assistant(tool_calls) 与其结果一同进总结
        List<ModelMessage> messages = List.of(
                ModelMessage.user("q1"),
                assistantWithCall("c1"), ModelMessage.tool("c1", LONG_TEXT),
                ModelMessage.user("q2"),
                ModelMessage.assistant("a2"));
        List<ModelMessage> compacted = compactor.compact(messages, text -> "s");
        assertNotEquals(ModelMessage.Role.TOOL, compacted.get(1).role());
    }

    @Test
    void fallsBackToPrunedWhenSummarizerFails() {
        ContextCompactor compactor = new ContextCompactor(1, 2);
        List<ModelMessage> messages = List.of(
                ModelMessage.user(LONG_TEXT), ModelMessage.tool("c1", LONG_TEXT),
                ModelMessage.user("a"), ModelMessage.user("b"));
        List<ModelMessage> compacted = compactor.compact(messages, text -> {
            throw new RuntimeException("model unavailable");
        });
        // 降级为裁剪结果：无 summary 消息，旧 tool 被占位符替换，尾部保留
        assertEquals(4, compacted.size());
        assertEquals(ContextCompactor.TRUNCATED_PLACEHOLDER, compacted.get(1).content());
        assertEquals("a", compacted.get(2).content());
    }

    @Test
    void pinsLeadingSystemMessagesOutOfCompaction() {
        ContextCompactor compactor = new ContextCompactor(1, 1);
        List<ModelMessage> messages = List.of(
                ModelMessage.system("system prompt"),
                ModelMessage.user(LONG_TEXT),
                ModelMessage.user("tail"));
        List<ModelMessage> compacted = compactor.compact(messages, text -> "s");
        assertEquals(ModelMessage.Role.SYSTEM, compacted.get(0).role());
        assertEquals("system prompt", compacted.get(0).content());
    }

    @Test
    void noCompressionWhenHistoryShorterThanTail() {
        ContextCompactor compactor = new ContextCompactor(1, 20);
        List<ModelMessage> messages = List.of(
                ModelMessage.user(LONG_TEXT), ModelMessage.assistant(LONG_TEXT));
        // 超阈值但消息数不足 pruneTurnAge：裁剪与总结均不生效，原样返回
        List<ModelMessage> compacted = compactor.compact(messages, text -> "s");
        assertEquals(2, compacted.size());
        assertEquals(LONG_TEXT, compacted.get(0).content());
    }

    private static ModelMessage assistantWithCall(String callId) {
        return new ModelMessage(ModelMessage.Role.ASSISTANT, "",
                List.of(new ToolCall(callId, "read_file", "{}")), "", "");
    }
}
