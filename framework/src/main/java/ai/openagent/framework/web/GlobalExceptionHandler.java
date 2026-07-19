package ai.openagent.framework.web;

import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.AbstractException;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.RemoteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 *
 * <p>
 * 拦截业务代码抛出的三层异常（Client/Service/Remote）并统一转换为
 * fastclaw 兼容的错误响应：HTTP 状态码 + {@code {"error": "..."}} 响应体。
 * 前端（搬自 fastclaw web）依赖该 wire 协议，因此不使用 Result 包装。
 * </p>
 *
 * <p>
 * 错误码 → HTTP 状态码映射：
 * <ul>
 *   <li>{@link BaseErrorCode#RESOURCE_NOT_FOUND} → 404</li>
 *   <li>{@link BaseErrorCode#RESOURCE_CONFLICT} → 409</li>
 *   <li>{@link BaseErrorCode#SERVICE_TIMEOUT_ERROR} → 429（排队等待超时等，
 *       对齐 fastclaw 队列满的 Too Many Requests 语义）</li>
 *   <li>{@link BaseErrorCode#SERVICE_UNAVAILABLE_ERROR} → 503</li>
 *   <li>{@link BaseErrorCode#UNAUTHORIZED} → 401</li>
 *   <li>{@link BaseErrorCode#FORBIDDEN} → 403</li>
 *   <li>其余 A 类（ClientException）→ 400</li>
 *   <li>C 类（RemoteException）→ 502</li>
 *   <li>其余 B 类（ServiceException / 兜底）→ 500</li>
 * </ul>
 * SSE / 流式接口在响应已开始后发生的异常不经过此处理器，由流式组件自行兜底
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截参数验证异常
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validExceptionHandler(
            HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        String exceptionStr = bindingResult.getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("invalid request");
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return errorResponse(HttpStatus.BAD_REQUEST, exceptionStr);
    }

    /**
     * 拦截缺少必填请求参数异常
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameterHandler(
            HttpServletRequest request, MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " required";
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), message);
        return errorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 拦截应用内抛出的三层业务异常
     */
    @ExceptionHandler(value = {AbstractException.class})
    public ResponseEntity<Map<String, Object>> abstractException(
            HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), ex, ex.getCause());
        } else {
            log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), ex.toString());
        }
        return errorResponse(httpStatusOf(ex), ex.getErrorMessage());
    }

    /**
     * 拦截静态资源缺失异常（保持 404 语义，避免被 Throwable 兜底转成 500）
     */
    @ExceptionHandler(value = NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResourceFoundHandler(
            HttpServletRequest request, NoResourceFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "not found");
    }

    /**
     * 拦截未捕获异常（兜底）
     */
    @ExceptionHandler(value = Throwable.class)
    public ResponseEntity<Map<String, Object>> defaultErrorHandler(
            HttpServletRequest request, Throwable throwable) {
        // SSE/异步请求在响应开始后出错，不宜再返回 JSON，直接记录后忽略
        if (isAsyncRequest(request)) {
            log.debug("[{}] {} async request error suppressed: {}", 
                    request.getMethod(), getUrl(request), throwable.getClass().getSimpleName());
            return null;
        }
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 判断是否为 SSE 或异步流式请求
     */
    private boolean isAsyncRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        // 检查是否已由异步上下文处理（比如 ChatSseStream 响应已开始）
        return request.getAsyncContext() != null;
    }

    /**
     * 三层异常 → HTTP 状态码映射
     */
    private HttpStatus httpStatusOf(AbstractException ex) {
        if (BaseErrorCode.RESOURCE_NOT_FOUND.code().equals(ex.getErrorCode())) {
            return HttpStatus.NOT_FOUND;
        }
        if (BaseErrorCode.RESOURCE_CONFLICT.code().equals(ex.getErrorCode())) {
            return HttpStatus.CONFLICT;
        }
        if (BaseErrorCode.SERVICE_TIMEOUT_ERROR.code().equals(ex.getErrorCode())) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (BaseErrorCode.SERVICE_UNAVAILABLE_ERROR.code().equals(ex.getErrorCode())) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (BaseErrorCode.UNAUTHORIZED.code().equals(ex.getErrorCode())) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (BaseErrorCode.FORBIDDEN.code().equals(ex.getErrorCode())) {
            return HttpStatus.FORBIDDEN;
        }
        if (ex instanceof ClientException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ex instanceof RemoteException) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * fastclaw 兼容的错误响应体：{"error": "..."}
     */
    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private String getUrl(HttpServletRequest request) {
        return Optional.ofNullable(request.getQueryString())
                .filter(query -> !query.isBlank())
                .map(query -> request.getRequestURL().toString() + "?" + query)
                .orElseGet(() -> request.getRequestURL().toString());
    }
}
