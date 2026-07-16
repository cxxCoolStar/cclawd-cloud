package ai.openagent.framework.web;

import ai.openagent.framework.convention.Result;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 拦截指定异常并统一转换为 {@link Result} 返回前端，同时记录日志。
 * SSE / 流式接口在响应已开始后发生的异常不经过此处理器，
 * 由 {@link SseEmitterSender} 自行兜底
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截参数验证异常
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        String exceptionStr = bindingResult.getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("");
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        return Results.failure(BaseErrorCode.PARAM_VERIFY_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截应用内抛出的三层业务异常
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        if (ex.getCause() != null) {
            log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), ex, ex.getCause());
            return Results.failure(ex);
        }
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), ex.toString());
        return Results.failure(ex);
    }

    /**
     * 拦截未捕获异常（兜底）
     */
    @ExceptionHandler(value = Throwable.class)
    public Result<Void> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    private String getUrl(HttpServletRequest request) {
        return Optional.ofNullable(request.getQueryString())
                .filter(query -> !query.isBlank())
                .map(query -> request.getRequestURL().toString() + "?" + query)
                .orElseGet(() -> request.getRequestURL().toString());
    }
}
