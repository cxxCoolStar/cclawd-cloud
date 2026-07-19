package ai.openagent.framework.identity;

import java.util.Optional;

/**
 * 请求级身份上下文
 *
 * <p>
 * 认证过滤器在请求进入时写入已认证身份，业务代码经 {@link #requireUserId()}
 * 获取当前用户；请求结束时必须 {@link #clear()}，避免线程复用导致身份串号。
 * ThreadLocal 不跨线程边界——异步路径（如 AgentRunCoordinator）需在提交时
 * 快照 userId 传入，而非在执行线程再读本上下文
 * </p>
 */
public final class RequestContext {

    private static final ThreadLocal<RequestIdentity> CURRENT = new ThreadLocal<>();

    /**
     * 写入当前请求的身份（由认证过滤器调用）
     */
    public static void set(RequestIdentity identity) {
        CURRENT.set(identity);
    }

    /**
     * 当前请求的身份（未认证请求为空）
     */
    public static Optional<RequestIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * 当前请求的用户 ID；无身份上下文时抛出异常（调用方应保证处于已认证请求中）
     */
    public static String requireUserId() {
        return current()
                .map(RequestIdentity::userId)
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new IllegalStateException("no authenticated identity in request context"));
    }

    /**
     * 清理当前线程的身份（请求结束必须调用）
     */
    public static void clear() {
        CURRENT.remove();
    }

    private RequestContext() {
    }
}
