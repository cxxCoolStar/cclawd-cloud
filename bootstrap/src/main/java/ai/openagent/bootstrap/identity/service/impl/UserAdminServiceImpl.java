package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.UserAdminService;
import ai.openagent.bootstrap.persistence.AuthSessionRepository;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.bootstrap.persistence.UserRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户管理服务实现（V9 M2 RBAC）
 *
 * <p>
 * 全部操作要求 super_admin，普通用户一律 403；停用用户立即失效其登录
 * 会话，删除用户不允许作用于自己（防止把最后一个管理入口锁死）
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserAdminServiceImpl implements UserAdminService {

    private static final Set<String> ROLES = Set.of("super_admin", "user");
    private static final Set<String> STATUSES = Set.of("active", "disabled");

    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public List<CurrentUserVO.UserVO> listUsers() {
        requireSuperAdmin();
        return userRepository.listAll().stream().map(CurrentUserVO.UserVO::from).toList();
    }

    @Override
    public CurrentUserVO.UserVO createUser(
            String username, String email, String password, String displayName, String role, Integer agentQuota) {
        requireSuperAdmin();
        if (username == null || username.isBlank()) {
            throw new ClientException("username required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (email == null || email.isBlank()) {
            throw new ClientException("email required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (password == null || password.length() < AuthServiceImpl.MIN_PASSWORD_LENGTH) {
            throw new ClientException(
                    "password must be at least " + AuthServiceImpl.MIN_PASSWORD_LENGTH + " characters",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        String effectiveRole = role == null || role.isBlank() ? "user" : role;
        if (!ROLES.contains(effectiveRole)) {
            throw new ClientException("unknown role: " + effectiveRole, BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        String trimmedUsername = username.trim();
        String trimmedEmail = email.trim();
        if (userRepository.findByUsername(trimmedUsername).isPresent()) {
            throw new ClientException("username already registered", BaseErrorCode.RESOURCE_CONFLICT);
        }
        if (userRepository.findByEmail(trimmedEmail).isPresent()) {
            throw new ClientException("email already registered", BaseErrorCode.RESOURCE_CONFLICT);
        }
        String id = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        userRepository.insert(new UserRecord(
                id,
                trimmedUsername,
                trimmedEmail,
                effectiveRole,
                displayName == null || displayName.isBlank() ? trimmedUsername : displayName.trim(),
                "active",
                passwordEncoder.encode(password),
                "",
                agentQuota != null ? agentQuota : -1,
                System.currentTimeMillis()));
        return CurrentUserVO.UserVO.from(userRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException("created user missing: " + id)));
    }

    @Override
    public CurrentUserVO.UserVO updateUser(
            String id, String displayName, String role, String status, Integer agentQuota) {
        requireSuperAdmin();
        UserRecord user = requireUser(id);
        if (role != null && !ROLES.contains(role)) {
            throw new ClientException("unknown role: " + role, BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new ClientException("unknown status: " + status, BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        userRepository.updateAdmin(id, displayName, role, status, agentQuota);
        if (status != null && !"active".equals(status)) {
            // 停用立即生效：踢掉全部登录会话
            sessionRepository.deleteByUser(id);
        }
        return CurrentUserVO.UserVO.from(requireUser(user.id()));
    }

    @Override
    public void deleteUser(String id) {
        requireSuperAdmin();
        if (id.equals(RequestContext.requireUserId())) {
            throw new ClientException("cannot delete your own account", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        requireUser(id);
        sessionRepository.deleteByUser(id);
        userRepository.delete(id);
    }

    @Override
    public void resetPassword(String id, String password) {
        requireSuperAdmin();
        requireUser(id);
        if (password == null || password.length() < AuthServiceImpl.MIN_PASSWORD_LENGTH) {
            throw new ClientException(
                    "password must be at least " + AuthServiceImpl.MIN_PASSWORD_LENGTH + " characters",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        userRepository.updatePasswordHash(id, passwordEncoder.encode(password));
        sessionRepository.deleteByUser(id);
    }

    private UserRecord requireUser(String id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> new ClientException("user not found", BaseErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * RBAC 闸门：仅 super_admin 可调用管理接口，普通用户 403
     * （/api/users 与 /api/admin/registration 共用）
     */
    public static void requireSuperAdmin() {
        RequestIdentity identity = RequestContext.current()
                .orElseThrow(() -> new ClientException("unauthorized", BaseErrorCode.UNAUTHORIZED));
        if (!identity.isPlatformAdmin()) {
            throw new ClientException("super_admin required", BaseErrorCode.FORBIDDEN);
        }
    }
}
