package ai.openagent.bootstrap.identity;

/**
 * 身份域常量
 *
 * <p>
 * 种子本地用户 ID。V9 起业务代码经 {@code RequestContext} 获取当前用户；
 * 本常量仅保留给种子数据（DataSeeder）、公开端点的匿名回退与存量测试，
 * 标识"多用户改造前"的历史数据归属
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
