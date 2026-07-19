package ai.openagent.bootstrap.identity.controller.request;

/**
 * 更新当前用户资料请求（对齐前端 updateMe()：{displayName, avatarUrl}；
 * 字段缺失表示保持不变）
 *
 * @param displayName 显示名
 * @param avatarUrl   头像地址
 */
public record UpdateMeRequest(String displayName, String avatarUrl) {}
