package ai.openagent.agent.eval;

import java.util.Collections;
import java.util.Map;
import lombok.Data;

/**
 * Eval 测试用例 POJO，对应 YAML 结构
 */
@Data
public class EvalCase {

    /**
     * 用例唯一标识
     */
    private String id;

    /**
     * 用例名称
     */
    private String name;

    /**
     * 分类（如 core_logic, exception_input, efficiency 等）
     */
    private String category;

    /**
     * 优先级（P0, P1, P2）
     */
    private String priority;

    /**
     * 输入提示词
     */
    private String input;

    /**
     * 预期结果定义
     */
    private EvalExpected expected;

    /**
     * 评分配置
     */
    private EvalScoring scoring;

    /**
     * 测试夹具（前置条件）
     */
    private EvalFixture fixtures;

    /**
     * 其他备注
     */
    private String notes;

    /**
     * 扩展字段
     */
    private Map<String, Object> extensions = Collections.emptyMap();

    /**
     * 校验必含字段
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("EvalCase id is required");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("EvalCase input is required");
        }
        if (expected == null) {
            throw new IllegalArgumentException("EvalCase expected is required");
        }
    }
}
