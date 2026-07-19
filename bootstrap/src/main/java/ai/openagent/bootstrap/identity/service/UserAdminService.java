package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import java.util.List;

/**
 * 用户管理服务（V9 M2 RBAC：仅 super_admin 可用）
 */
public interface UserAdminService {

    /**
     * 列出全部用户
     */
    List<CurrentUserVO.UserVO> listUsers();

    /**
     * 创建用户（角色缺省 user；用户名/邮箱重复 409）
     */
    CurrentUserVO.UserVO createUser(
            String username, String email, String password, String displayName, String role, Integer agentQuota);

    /**
     * 管理面更新（null 字段不动）
     */
    CurrentUserVO.UserVO updateUser(String id, String displayName, String role, String status, Integer agentQuota);

    /**
     * 删除用户及其登录会话；不允许删除自己（400）
     */
    void deleteUser(String id);

    /**
     * 重置用户密码并使其全部会话失效
     */
    void resetPassword(String id, String password);
}
