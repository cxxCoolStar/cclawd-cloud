package ai.openagent.agent.eval.grader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected;
import ai.openagent.agent.eval.EvalScoring;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolContractGraderTest {

    private final ToolContractGrader grader = new ToolContractGrader();

    @Test
    void failedInvocationDoesNotSatisfyRequiredTool() {
        EvalContext context = EvalContext.builder()
                .toolCalls(List.of(call("web_search", "Tool failed [TOOL_NOT_ENABLED]: not configured")))
                .build();

        var result = grader.grade(evalCaseRequiring("web_search"), context);

        assertFalse(result.passed());
        assertTrue(result.reason().contains("必需工具未成功执行"));
    }

    @Test
    void laterSuccessfulRetrySatisfiesRequiredTool() {
        EvalContext context = EvalContext.builder()
                .toolCalls(List.of(
                        call("exec", "Tool failed [TOOL_EXECUTION_FAILED]: temporary failure"),
                        call("exec", "5050")))
                .build();

        assertTrue(grader.grade(evalCaseRequiring("exec"), context).passed());
    }

    private static EvalContext.ToolCall call(String name, String result) {
        return EvalContext.ToolCall.builder()
                .toolName(name)
                .arguments("{}")
                .result(result)
                .sequence(1)
                .build();
    }

    private static EvalCase evalCaseRequiring(String toolName) {
        EvalExpected.ToolExpected tools = new EvalExpected.ToolExpected();
        tools.setRequired(List.of(toolName));

        EvalExpected expected = new EvalExpected();
        expected.setTools(tools);

        EvalScoring scoring = new EvalScoring();
        scoring.setProcessViolationPenalty(10);

        EvalCase evalCase = new EvalCase();
        evalCase.setExpected(expected);
        evalCase.setScoring(scoring);
        return evalCase;
    }
}
