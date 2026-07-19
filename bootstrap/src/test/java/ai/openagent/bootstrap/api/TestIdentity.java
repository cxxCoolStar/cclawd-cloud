package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;

/**
 * 测试身份工具
 *
 * <p>
 * Web 层测试由 {@link TestAuthSessionFilter} 自动注入 cookie 会话；
 * 绕过 Web 层直接调用服务/协调器的用例（如 AgentRunCoordinator.start）
 * 经 {@link #callAs} 显式包裹身份，与生产代码从 RequestContext 取身份
 * 的路径保持一致
 * </p>
 */
public final class TestIdentity {

    /**
     * 测试会话令牌（由 TestAuthSessionSeeder 落库、TestAuthSessionFilter 注入）
     */
    public static final String TEST_SESSION_TOKEN = "test-session-token";

    /**
     * 种子本地用户的测试身份
     */
    public static RequestIdentity localUser() {
        return new RequestIdentity(
                IdentityConstant.LOCAL_USER_ID, null, null, "super_admin", "test");
    }

    /**
     * 以指定身份执行调用（请求结束清理语义与 AuthFilter 一致）
     */
    public static <T> T callAs(RequestIdentity identity, ThrowingSupplier<T> call) throws Exception {
        RequestContext.set(identity);
        try {
            return call.get();
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 允许抛出受检异常的 Supplier
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        T get() throws Exception;
    }

    private TestIdentity() {
    }
}
