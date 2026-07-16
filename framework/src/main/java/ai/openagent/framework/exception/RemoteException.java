package ai.openagent.framework.exception;

import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.errorcode.IErrorCode;

/**
 * 远程服务调用异常
 * <p>
 * 调用第三方服务（如模型供应商 API）失败时向上抛出的异常
 * </p>
 */
public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
