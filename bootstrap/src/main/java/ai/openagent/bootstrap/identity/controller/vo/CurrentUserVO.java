package ai.openagent.bootstrap.identity.controller.vo;

import ai.openagent.bootstrap.persistence.UserRecord;

/**
 * 当前用户身份视图对象（fastclaw 协议形状：{ok, user, authMethod, readOnly, deployMode}）
 *
 * @param ok         恒为 true（fastclaw 协议字段；失败响应走 {"ok":false,"error":...} 形状）
 * @param user       用户信息
 * @param authMethod 认证方式（cookie 会话为 cookie）
 * @param readOnly   是否只读会话
 * @param deployMode 部署模式（self-hosted / hosted）
 */
public record CurrentUserVO(boolean ok, UserVO user, String authMethod, boolean readOnly, String deployMode) {

    /**
     * 用户信息
     *
     * @param id          用户 ID
     * @param username    用户名
     * @param email       邮箱
     * @param role        角色
     * @param displayName 显示名
     * @param avatarUrl   头像地址
     * @param status      状态
     * @param agentQuota  智能体配额（-1 表示不限制）
     */
    public record UserVO(
            String id,
            String username,
            String email,
            String role,
            String displayName,
            String avatarUrl,
            String status,
            int agentQuota) {

        /**
         * 由持久化记录装配
         */
        public static UserVO from(UserRecord record) {
            return new UserVO(
                    record.id(),
                    record.username(),
                    record.email(),
                    record.role(),
                    record.displayName(),
                    record.avatarUrl(),
                    record.status(),
                    record.agentQuota());
        }
    }

    /**
     * 已认证用户的身份响应
     */
    public static CurrentUserVO from(UserRecord record, String authMethod) {
        return new CurrentUserVO(true, UserVO.from(record), authMethod, false, "self-hosted");
    }
}
