package ai.openagent.infra.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.infra.ai.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 流式 tool call 分片聚合测试（对齐 fastclaw ChatStream 聚合规则）
 */
class ToolCallAccumulatorTest {

    @Test
    void mergesArgumentFragmentsOfOneToolCall() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        accumulator.accept(0, "call_abc", "read_file", "");
        accumulator.accept(0, "", "", "{\"path\":");
        accumulator.accept(0, "", "", "\"README.md\"}");

        List<ToolCall> calls = accumulator.complete();
        assertEquals(1, calls.size());
        assertEquals("call_abc", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
        assertEquals("{\"path\":\"README.md\"}", calls.get(0).arguments());
    }

    @Test
    void keepsStableOrderAcrossInterleavedIndexes() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        // 分片乱序到达：index 1 的首分片先于 index 0 的尾分片
        accumulator.accept(0, "call_a", "get_current_time", "{\"timezone\":");
        accumulator.accept(1, "call_b", "calculator", "{\"expression\":");
        accumulator.accept(0, "", "", "\"Asia/Shanghai\"}");
        accumulator.accept(1, "", "", "\"17*8\"}");

        List<ToolCall> calls = accumulator.complete();
        assertEquals(2, calls.size());
        assertEquals("call_a", calls.get(0).id());
        assertEquals("{\"timezone\":\"Asia/Shanghai\"}", calls.get(0).arguments());
        assertEquals("call_b", calls.get(1).id());
        assertEquals("{\"expression\":\"17*8\"}", calls.get(1).arguments());
    }

    @Test
    void lateIdOverridesAndNameConcatenates() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        // 部分供应商把函数名也拆片；后到的非空 id 覆盖
        accumulator.accept(0, "", "read_", "");
        accumulator.accept(0, "call_late", "file", "{}");

        List<ToolCall> calls = accumulator.complete();
        assertEquals("call_late", calls.get(0).id());
        assertEquals("read_file", calls.get(0).name());
    }

    @Test
    void emptyAccumulatorReportsEmpty() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        assertTrue(accumulator.isEmpty());
        assertTrue(accumulator.complete().isEmpty());
    }
}
