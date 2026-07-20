package ai.openagent.agent.eval.grader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalExpected;
import ai.openagent.agent.eval.EvalScoring;
import org.junit.jupiter.api.Test;

class LatencyBudgetGraderTest {

    @Test
    void appliesConfiguredBudgetMultiplier() {
        EvalCase evalCase = evalCaseWithLatencyBudget(1_000);
        EvalContext context = EvalContext.builder().latencyMs(3_500L).build();

        assertFalse(new LatencyBudgetGrader().grade(evalCase, context).passed());
        assertTrue(new LatencyBudgetGrader(4.0).grade(evalCase, context).passed());
    }

    @Test
    void rejectsInvalidMultiplier() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyBudgetGrader(0));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBudgetGrader(Double.NaN));
    }

    private static EvalCase evalCaseWithLatencyBudget(long latencyMs) {
        EvalExpected.MaxLimits max = new EvalExpected.MaxLimits();
        max.setLatencyMs(latencyMs);

        EvalExpected expected = new EvalExpected();
        expected.setMax(max);

        EvalScoring scoring = new EvalScoring();
        scoring.setProcessViolationPenalty(10);

        EvalCase evalCase = new EvalCase();
        evalCase.setExpected(expected);
        evalCase.setScoring(scoring);
        return evalCase;
    }
}
