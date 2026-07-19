package ai.openagent.agent.eval;

import java.util.List;
import lombok.Data;

/**
 * Eval 测试套件，包含多个测试用例
 */
@Data
public class EvalSuite {

    /**
     * 套件名称
     */
    private String name;

    /**
     * 套件版本
     */
    private String version;

    /**
     * 包含的测试用例列表
     */
    private List<EvalCase> cases;

    /**
     * 默认评分配置（可被用例级别覆盖）
     */
    private EvalScoring defaultScoring;

    /**
     * 获取指定 ID 的测试用例
     */
    public EvalCase getCase(String caseId) {
        if (cases == null) {
            return null;
        }
        return cases.stream()
                .filter(c -> caseId.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }
}
