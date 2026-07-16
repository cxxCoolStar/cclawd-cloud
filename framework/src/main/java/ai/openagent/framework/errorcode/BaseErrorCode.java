package ai.openagent.framework.errorcode;

/**
 * 基础错误码定义枚举
 *
 * <p>
 * 定义系统中常用的标准错误码，遵循阿里巴巴错误码规范：
 * <ul>
 *   <li>A 类错误：用户端错误（Client Error）</li>
 *   <li>B 类错误：系统执行错误（Service Error）</li>
 *   <li>C 类错误：第三方服务错误（Remote Error）</li>
 * </ul>
 * 业务域专属错误码在各自域内定义（实现 {@link IErrorCode}），此处只放跨域通用错误码。
 * </p>
 */
public enum BaseErrorCode implements IErrorCode {

    // ========== A 类错误：用户端错误 ==========

    /**
     * 一级宏观错误码：客户端错误
     */
    CLIENT_ERROR("A000001", "用户端错误"),

    /**
     * 请求参数校验失败
     */
    PARAM_VERIFY_ERROR("A000110", "请求参数校验失败"),

    /**
     * 请求的资源不存在
     */
    RESOURCE_NOT_FOUND("A000400", "请求的资源不存在"),

    /**
     * 资源并发冲突（如同一会话的聊天回合仍在进行中）
     */
    RESOURCE_CONFLICT("A000401", "资源并发冲突"),

    // ========== B 类错误：系统执行错误 ==========

    /**
     * 一级宏观错误码：系统执行出错
     */
    SERVICE_ERROR("B000001", "系统执行出错"),

    /**
     * 二级宏观错误码：系统执行超时
     */
    SERVICE_TIMEOUT_ERROR("B000100", "系统执行超时"),

    // ========== C 类错误：第三方服务错误 ==========

    /**
     * 一级宏观错误码：调用第三方服务出错
     */
    REMOTE_ERROR("C000001", "调用第三方服务出错"),

    /**
     * 模型服务调用失败
     */
    MODEL_INVOKE_ERROR("C000100", "模型服务调用失败");

    /**
     * 错误码
     */
    private final String code;

    /**
     * 错误消息
     */
    private final String message;

    BaseErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
