package ai.openagent.bootstrap.identity.service;

import ai.openagent.framework.identity.RequestIdentity;
import java.util.Optional;

/**
 * 认证服务
 *
 * <p>
 * 注册 / 登录 / 登出 / 会话校验。注册受 {@code openagent.registration-open}
 * 配置门控；首个设置密码的用户（全新部署引导）自动成为 super_admin 且不受
 * 门控——种子 local-user 无密码，不影响"首用户"判定
 * </p>
 */
public interface AuthService {

    /**
     * 注册新用户（校验唯一性后落库，角色按引导/门控规则确定）并签发会话
     */
    IssuedSession register(String username, String email, String password, String displayName);

    /**
     * 校验登录（login 为用户名或邮箱）并签发会话；失败抛出 401 业务异常
     */
    IssuedSession login(String login, String password);

    /**
     * 删除会话（幂等）
     */
    void logout(String token);

    /**
     * onboard 引导建号（V9 M2）：账密字段任一非空时校验齐备并创建 super_admin，
     * 仅当库内尚无密码用户（全新部署）时生效；字段全空或已完成引导返回空
     */
    Optional<ai.openagent.bootstrap.persistence.UserRecord> bootstrapAdmin(
            String username, String email, String password, String displayName);

    /**
     * 按会话令牌解析请求身份（过期会话惰性剔除）；无效返回空
     */
    Optional<RequestIdentity> authenticate(String token);
}
