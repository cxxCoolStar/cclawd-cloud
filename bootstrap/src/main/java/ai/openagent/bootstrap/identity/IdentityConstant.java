package ai.openagent.bootstrap.identity;

/**
 * 身份域常量
 *
 * <p>
 * 本地单用户模式下的固定用户 ID。多用户能力落地前，各业务域通过此常量
 * 获取当前用户，避免散落的字符串字面量；届时替换为真实的用户上下文
 * </p>
 */
public final class IdentityConstant {

    /**
     * 本地单用户模式的固定用户 ID（与种子数据一致）
     */
    public static final String LOCAL_USER_ID = "local-user";

    private IdentityConstant() {
    }
}
