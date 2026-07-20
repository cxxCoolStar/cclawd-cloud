package ai.openagent.agent.hook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HookRegistry 语义测试：验证钩子按注册顺序执行以及 fail-open 错误隔离机制
 */
class HookRegistryTest {

    private static HookContext context() {
        return new HookContext("run-1", "local-user", "default", "session-1");
    }

    private static AgentHook hook(HookPoint point, Runnable action) {
        return new AgentHook() {
            @Override
            public HookPoint point() {
                return point;
            }

            @Override
            public void onHook(HookContext context) {
                action.run();
            }
        };
    }

    @Test
    void firesHooksInRegistrationOrderPerPoint() {
        List<String> calls = new ArrayList<>();
        HookRegistry registry = new HookRegistry();
        registry.register(hook(HookPoint.POST_TURN, () -> calls.add("first")));
        registry.register(hook(HookPoint.POST_TURN, () -> calls.add("second")));
        registry.register(hook(HookPoint.BEFORE_MODEL_CALL, () -> calls.add("other-point")));

        registry.fire(HookPoint.POST_TURN, context());

        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void failingHookDoesNotAffectLaterHooksOrCaller() {
        List<String> calls = new ArrayList<>();
        HookRegistry registry = new HookRegistry();
        registry.register(hook(HookPoint.POST_TURN, () -> {
            throw new IllegalStateException("hook bug");
        }));
        registry.register(hook(HookPoint.POST_TURN, () -> calls.add("after")));

        assertDoesNotThrow(() -> registry.fire(HookPoint.POST_TURN, context()));
        assertEquals(List.of("after"), calls);
    }

    @Test
    void emptyRegistryIsSafe() {
        assertDoesNotThrow(() -> HookRegistry.empty().fire(HookPoint.POST_TURN, context()));
    }

    @Test
    void fireWritesPointIntoContext() {
        HookContext context = context();
        HookRegistry.empty().fire(HookPoint.AFTER_TOOL_CALL, context);
        assertEquals(HookPoint.AFTER_TOOL_CALL, context.point());
    }
}
