package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.IdentityService;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.bootstrap.persistence.UserRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 身份服务实现
 *
 * <p>
 * 当前用户来自 {@link RequestContext}（认证过滤器按会话 cookie 写入），
 * 持久层用户记录为唯一事实来源
 * </p>
 */
@Service
@RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public CurrentUserVO currentUser() {
        String authMethod = RequestContext.current()
                .map(RequestIdentity::authenticationMethod)
                .orElse("none");
        return CurrentUserVO.from(currentUserRecord(), authMethod);
    }

    @Override
    public CurrentUserVO updateMe(String displayName, String avatarUrl) {
        UserRecord user = currentUserRecord();
        userRepository.updateProfile(
                user.id(),
                displayName != null ? displayName : user.displayName(),
                avatarUrl != null ? avatarUrl : user.avatarUrl());
        return currentUser();
    }

    @Override
    public void changePassword(String oldPassword, String newPassword) {
        UserRecord user = currentUserRecord();
        if (user.passwordHash().isBlank() || !passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new ClientException("old password is incorrect", BaseErrorCode.UNAUTHORIZED);
        }
        if (newPassword == null || newPassword.length() < AuthServiceImpl.MIN_PASSWORD_LENGTH) {
            throw new ClientException(
                    "password must be at least " + AuthServiceImpl.MIN_PASSWORD_LENGTH + " characters",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        userRepository.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword));
    }

    /**
     * 当前请求用户记录；会话对应的用户已被删除时按未认证处理
     */
    private UserRecord currentUserRecord() {
        return userRepository
                .findById(RequestContext.requireUserId())
                .orElseThrow(() -> new ClientException("user not found", BaseErrorCode.UNAUTHORIZED));
    }
}
