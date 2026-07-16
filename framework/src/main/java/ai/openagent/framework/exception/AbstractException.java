package ai.openagent.framework.exception;

import ai.openagent.framework.errorcode.IErrorCode;
import java.util.Optional;
import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * 三层异常体系抽象基类
 * <p>
 * 抽象项目中的三类异常：客户端异常、服务端异常以及远程服务调用异常
 * </p>
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    public final String errorCode;

    public final String errorMessage;

    public AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(message) ? message : null)
                .orElse(errorCode.message());
    }
}
