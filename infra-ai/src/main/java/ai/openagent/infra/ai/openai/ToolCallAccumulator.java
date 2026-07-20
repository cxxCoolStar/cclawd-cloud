package ai.openagent.infra.ai.openai;

import ai.openagent.infra.ai.model.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 流式 tool call 分片聚合器
 *
 * <p>
 * 聚合规则：以 delta 中的 {@code index} 定位同一 tool call，id/type 后到非空值覆盖，
 * 函数名与 arguments 按到达顺序拼接；最终按 index 升序输出，
 * 保证一次响应包含多个 tool calls 时执行顺序稳定
 * </p>
 */
final class ToolCallAccumulator {

    /**
     * 聚合中的单个 tool call（可变，聚合完成后转为不可变 ToolCall）
     */
    private static final class Building {
        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private final Map<Integer, Building> byIndex = new TreeMap<>();

    /**
     * 合并一个 tool call 增量分片
     *
     * @param index          分片所属 tool call 的位置（OpenAI 流式协议字段）
     * @param id             本分片携带的 ID（通常只在首分片出现）
     * @param nameDelta      函数名增量
     * @param argumentsDelta arguments JSON 字符串增量
     */
    void accept(int index, String id, String nameDelta, String argumentsDelta) {
        Building building = byIndex.computeIfAbsent(index, ignored -> new Building());
        if (id != null && !id.isEmpty()) {
            building.id = id;
        }
        if (nameDelta != null) {
            building.name.append(nameDelta);
        }
        if (argumentsDelta != null) {
            building.arguments.append(argumentsDelta);
        }
    }

    boolean isEmpty() {
        return byIndex.isEmpty();
    }

    /**
     * 输出聚合完成的 tool call 列表（按 index 升序的稳定顺序）
     */
    List<ToolCall> complete() {
        List<ToolCall> calls = new ArrayList<>(byIndex.size());
        for (Building building : byIndex.values()) {
            calls.add(new ToolCall(building.id, building.name.toString(), building.arguments.toString()));
        }
        return calls;
    }
}
