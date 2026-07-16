package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;

/**
 * 身份服务接口
 * <p>
 * 本地单用户模式：身份恒为种子用户，无真实认证；
 * 多用户能力落地时在此扩展登录/登出/会话管理
 * </p>
 */
public interface IdentityService {

    /**
     * 查询当前用户身份
     */
    CurrentUserVO currentUser();
}
