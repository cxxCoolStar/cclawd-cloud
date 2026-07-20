package ai.openagent.bootstrap.tool.websearch;

/**
 * 搜索失败异常
 *
 * <p>
 * retriable=true（网络错误、超时、429、5xx、无结果）时链回退到下一个
 * provider；retriable=false（参数错误、配置错误、4xx）直接终止链——
 * 配置 bug 快速暴露，不被回退掩盖
 * </p>
 */
public class WebSearchException extends RuntimeException {

    /**
     * 请求成功但结果为空，可回退到下一个 provider
     */
    public static final WebSearchException NO_RESULTS = new WebSearchException("no results", true);

    private final boolean retriable;

    public WebSearchException(String message, boolean retriable) {
        super(message);
        this.retriable = retriable;
    }

    public WebSearchException(String message, boolean retriable, Throwable cause) {
        super(message, cause);
        this.retriable = retriable;
    }

    public boolean retriable() {
        return retriable;
    }
}
