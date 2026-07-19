package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;

/**
 * 身份服务接口
 *
 * <p>
 * 当前用户身份查询与自助资料维护（显示名 / 头像 / 密码），
 * 身份来自 {@code RequestContext}（认证过滤器写入）
 * </p>
 */
public interface IdentityService {

    /**
     * 查询当前用户身份
     */
    CurrentUserVO currentUser();

    /**
     * 更新当前用户显示名与头像（null 字段保持不变）
     */
    CurrentUserVO updateMe(String displayName, String avatarUrl);

    /**
     * 修改当前用户密码（校验旧密码）
     */
    void changePassword(String oldPassword, String newPassword);
}
