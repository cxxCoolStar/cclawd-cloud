package ai.openagent.infra.ai;

import ai.openagent.framework.exception.RemoteException;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * 主备切换的模型服务装饰器（V8 方案 3.1）
 *
 * <p>
 * 主 provider 调用在<strong>首个 streamed delta 到达前</strong>遇到
 * retriable 错误（HTTP 429 / 5xx / 连接或读写超时等 IO 失败）时，切换
 * 备用 provider 重试整次调用；delta 已开始流出后不再切换——避免两个
 * provider 的输出拼接成一段不可读的回复。备用经
 * {@code openagent.llm.fallback.*} 配置，未配置时直通主 provider。
 * 切换打 WARN 日志（含原因与备用 provider 标识）。
 * 本类不依赖 Spring，由 bootstrap 装配为 Bean
 * </p>
 */
@Slf4j
public class FallbackLLMService implements LLMService {

    /**
     * OpenAiCompatibleLLMService 的 HTTP 错误消息前缀（用于提取状态码分类）
     */
    private static final Pattern HTTP_ERROR_PREFIX = Pattern.compile("^model request failed with HTTP (\\d+)");

    private final LLMService delegate;
    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;

    public FallbackLLMService(
            LLMService delegate, String fallbackBaseUrl, String fallbackApiKey, String fallbackModel) {
        this.delegate = delegate;
        this.fallbackBaseUrl = fallbackBaseUrl == null ? "" : fallbackBaseUrl;
        this.fallbackApiKey = fallbackApiKey == null ? "" : fallbackApiKey;
        this.fallbackModel = fallbackModel == null ? "" : fallbackModel;
    }

    /**
     * 备用 provider 是否已配置（无 base-url/api-key 时装饰器直通）
     */
    public boolean fallbackConfigured() {
        return !fallbackBaseUrl.isBlank() && !fallbackApiKey.isBlank();
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelEventListener listener) {
        if (!fallbackConfigured()) {
            return delegate.stream(request, listener);
        }
        AtomicBoolean deltaSeen = new AtomicBoolean(false);
        ModelEventListener tracking = event -> {
            deltaSeen.set(true);
            listener.onEvent(event);
        };
        try {
            return delegate.stream(request, tracking);
        } catch (RemoteException error) {
            if (deltaSeen.get() || !retriable(error)) {
                throw error;
            }
            ModelRequest fallbackRequest = new ModelRequest(
                    new ModelProviderConfig(request.provider().type(), fallbackBaseUrl, fallbackApiKey),
                    fallbackModel.isBlank() ? request.model() : fallbackModel,
                    request.messages(),
                    request.tools(),
                    request.temperature(),
                    request.maxTokens());
            log.warn("[llm] 主 provider 首 delta 前失败，切换备用 provider，model={}, 原因：{}",
                    fallbackRequest.model(), error.getMessage());
            return delegate.stream(fallbackRequest, listener);
        }
    }

    /**
     * 失败分类：HTTP 429 / 5xx 与 IO 失败（连接超时、读写中断）可重试；
     * 4xx 参数/凭证错误与空响应等协议错误快速失败
     */
    private static boolean retriable(RemoteException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        Matcher matcher = HTTP_ERROR_PREFIX.matcher(message);
        if (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            return status == 429 || status >= 500;
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
