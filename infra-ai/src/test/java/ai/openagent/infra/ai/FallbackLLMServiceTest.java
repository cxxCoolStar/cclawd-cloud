package ai.openagent.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.RemoteException;
import ai.openagent.infra.ai.model.ModelEvent;
import ai.openagent.infra.ai.model.ModelProviderConfig;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FallbackLLMService 单测（V8 方案 3.1 必测行为：首 delta 前 retriable
 * 切换备用、delta 已流出不切换、非 retriable 快速失败、未配置直通）
 */
class FallbackLLMServiceTest {

    /**
     * 脚本化假 delegate：首次调用按 script 行为（可先吐 delta 再抛错），
     * 后续调用成功返回；记录所有收到的请求供断言
     */
    private static final class ScriptedLLMService implements LLMService {

        private final List<ModelRequest> requests = new ArrayList<>();
        private boolean emitDeltaBeforeFailure = false;
        private RuntimeException failure;
        private int calls = 0;

        @Override
        public ModelResponse stream(ModelRequest request, ModelEventListener listener) {
            requests.add(request);
            calls++;
            if (calls == 1 && failure != null) {
                if (emitDeltaBeforeFailure) {
                    listener.onEvent(new ModelEvent.TextDelta("partial"));
                }
                throw failure;
            }
            return new ModelResponse.Text("ok-" + calls, null, "");
        }
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new ModelProviderConfig("openai", "https://primary", "sk-primary"),
                "primary-model",
                List.of(),
                List.of(),
                null,
                null);
    }

    private static FallbackLLMService service(LLMService delegate) {
        return new FallbackLLMService(delegate, "https://backup", "sk-backup", "backup-model");
    }

    @Test
    void retriableHttpErrorBeforeFirstDeltaSwitchesToFallback() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException("model request failed with HTTP 500: boom");
        ModelResponse response = service(delegate).stream(request(), event -> {});
        assertInstanceOf(ModelResponse.Text.class, response);
        assertEquals(2, delegate.requests.size());
        ModelRequest fallbackRequest = delegate.requests.get(1);
        assertEquals("https://backup", fallbackRequest.provider().apiBase());
        assertEquals("sk-backup", fallbackRequest.provider().apiKey());
        assertEquals("backup-model", fallbackRequest.model());
    }

    @Test
    void http429IsRetriable() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException("model request failed with HTTP 429: rate limited");
        service(delegate).stream(request(), event -> {});
        assertEquals(2, delegate.requests.size());
    }

    @Test
    void ioFailureIsRetriable() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException(
                "model request failed: timeout", new IOException("timeout"), BaseErrorCode.MODEL_INVOKE_ERROR);
        service(delegate).stream(request(), event -> {});
        assertEquals(2, delegate.requests.size());
    }

    @Test
    void nonRetriableHttpErrorFailsFast() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException("model request failed with HTTP 400: bad request");
        RemoteException error =
                assertThrows(RemoteException.class, () -> service(delegate).stream(request(), event -> {}));
        assertTrue(error.getMessage().contains("400"));
        assertEquals(1, delegate.requests.size());
    }

    @Test
    void noSwitchAfterFirstDeltaSeen() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.emitDeltaBeforeFailure = true;
        delegate.failure = new RemoteException("model request failed with HTTP 500: boom");
        List<String> deltas = new ArrayList<>();
        RemoteException error = assertThrows(
                RemoteException.class,
                () -> service(delegate).stream(request(), event -> deltas.add(event.toString())));
        assertTrue(error.getMessage().contains("500"));
        assertEquals(1, delegate.requests.size());
        assertEquals(1, deltas.size());
    }

    @Test
    void passthroughWhenFallbackNotConfigured() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException("model request failed with HTTP 500: boom");
        FallbackLLMService service = new FallbackLLMService(delegate, "", "", "");
        assertTrue(!service.fallbackConfigured());
        assertThrows(RemoteException.class, () -> service.stream(request(), event -> {}));
        assertEquals(1, delegate.requests.size());
    }

    @Test
    void blankFallbackModelKeepsPrimaryModel() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        delegate.failure = new RemoteException("model request failed with HTTP 500: boom");
        FallbackLLMService service = new FallbackLLMService(delegate, "https://backup", "sk-backup", "");
        service.stream(request(), event -> {});
        assertEquals("primary-model", delegate.requests.get(1).model());
    }

    @Test
    void successOnPrimaryDoesNotTouchFallback() {
        ScriptedLLMService delegate = new ScriptedLLMService();
        ModelResponse response = service(delegate).stream(request(), event -> {});
        assertEquals("ok-1", ((ModelResponse.Text) response).content());
        assertEquals(1, delegate.requests.size());
    }
}
