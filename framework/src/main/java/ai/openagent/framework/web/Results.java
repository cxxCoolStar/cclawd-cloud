package ai.openagent.framework.web;

import ai.openagent.framework.convention.Result;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.AbstractException;
import java.util.Optional;

/**
 * 全局返回对象构造器
 * <p>
 * Controller 统一通过 {@code Results.success(...)} 构建成功响应；
 * failure 系列方法为包私有，仅供 {@link GlobalExceptionHandler} 使用，
 * 业务代码不应手工构建失败响应，而应抛出对应异常
 * </p>
 */
public final class Results {

    private Results() {
    }

    /**
     * 构造成功响应
     */
    public static Result<Void> success() {
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE);
    }

    /**
     * 构造带返回数据的成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setData(data);
    }

    /**
     * 构建服务端失败响应（兜底）
     */
    static Result<Void> failure() {
        return new Result<Void>()
                .setCode(BaseErrorCode.SERVICE_ERROR.code())
                .setMessage(BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 通过 {@link AbstractException} 构建失败响应
     */
    static Result<Void> failure(AbstractException abstractException) {
        String errorCode = Optional.ofNullable(abstractException.getErrorCode())
                .orElse(BaseErrorCode.SERVICE_ERROR.code());
        String errorMessage = Optional.ofNullable(abstractException.getErrorMessage())
                .orElse(BaseErrorCode.SERVICE_ERROR.message());
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 通过 errorCode、errorMessage 构建失败响应
     */
    static Result<Void> failure(String errorCode, String errorMessage) {
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }
}
