package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;

/**
 * 已签发的登录会话（认证成功结果：身份响应 + 会话令牌）
 *
 * @param user  当前用户身份响应（包含用户ID、用户名、昵称等基本信息）
 * @param token 会话令牌（由控制器写入 cookie）
 */
public record IssuedSession(CurrentUserVO user, String token) {}
