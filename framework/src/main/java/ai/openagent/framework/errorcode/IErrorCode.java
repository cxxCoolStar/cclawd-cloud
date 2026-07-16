package ai.openagent.framework.errorcode;

/**
 * 平台错误码抽象接口
 * <p>
 * 由各错误码枚举实现，配合三层异常体系（Client/Service/Remote）使用
 * </p>
 */
public interface IErrorCode {

    /**
     * 错误码
     */
    String code();

    /**
     * 错误信息
     */
    String message();
}
