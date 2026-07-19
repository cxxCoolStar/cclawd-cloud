package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.AuthConstant;
import ai.openagent.bootstrap.identity.controller.vo.CurrentUserVO;
import ai.openagent.bootstrap.identity.service.AuthService;
import ai.openagent.bootstrap.identity.service.IssuedSession;
import ai.openagent.bootstrap.identity.service.RegistrationSettingsService;
import ai.openagent.bootstrap.persistence.AuthSessionRecord;
import ai.openagent.bootstrap.persistence.AuthSessionRepository;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.bootstrap.persistence.UserRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.identity.RequestIdentity;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * 密码最小长度（与前端注册表单校验一致）
     */
    static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final RegistrationSettingsService registrationSettingsService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public IssuedSession register(String username, String email, String password, String displayName) {
        if (username == null || username.isBlank()) {
            throw new ClientException("username required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (email == null || email.isBlank()) {
            throw new ClientException("email required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ClientException(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        // 无密码用户 = 全新部署：首个注册者自动成为 super_admin 且不受门控，
        // 保证部署可引导（种子 local-user 无密码，不参与计数）
        boolean bootstrap = !userRepository.hasAnyPasswordUser();
        if (!bootstrap && !registrationSettingsService.isOpen()) {
            throw new ClientException("registration is closed", BaseErrorCode.FORBIDDEN);
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
                bootstrap ? "super_admin" : "user",
                displayName == null || displayName.isBlank() ? trimmedUsername : displayName.trim(),
                "active",
                passwordEncoder.encode(password),
                "",
                -1,
                System.currentTimeMillis()));
        return issue(userRepository
                .findById(id)
                .orElseThrow(() -> new IllegalStateException("registered user missing: " + id)));
    }

    @Override
    public IssuedSession login(String login, String password) {
        Optional<UserRecord> user = userRepository
                .findByUsername(login)
                .or(() -> userRepository.findByEmail(login));
        if (user.isEmpty()
                || user.get().passwordHash().isBlank()
                || !"active".equals(user.get().status())
                || !passwordEncoder.matches(password, user.get().passwordHash())) {
            // 用户不存在与密码错误统一报错，不暴露账号存在性（停用账号同口径）
            throw new ClientException("invalid username or password", BaseErrorCode.UNAUTHORIZED);
        }
        return issue(user.get());
    }

    @Override
    public void logout(String token) {
        sessionRepository.delete(token);
    }

    @Override
    public Optional<UserRecord> bootstrapAdmin(
            String username, String email, String password, String displayName) {
        // 三字段齐备才视为建号意图（前端 onboard 页总是三者同发）；
        // 字段不全按无账号诉求静默跳过，兼容只写供应商配置的存量调用
        boolean allProvided = username != null && !username.isBlank()
                && email != null && !email.isBlank()
                && password != null && !password.isBlank();
        if (!allProvided || userRepository.hasAnyPasswordUser()) {
            return Optional.empty();
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ClientException(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
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
                "super_admin",
                displayName == null || displayName.isBlank() ? trimmedUsername : displayName.trim(),
                "active",
                passwordEncoder.encode(password),
                "",
                -1,
                System.currentTimeMillis()));
        return userRepository.findById(id);
    }

    @Override
    public Optional<RequestIdentity> authenticate(String token) {
        return sessionRepository
                .findValid(token, System.currentTimeMillis())
                .flatMap(session -> userRepository.findById(session.userId()))
                .filter(user -> "active".equals(user.status()))
                .map(user -> new RequestIdentity(
                        user.id(), user.id(), user.id(), user.role(), AuthConstant.AUTH_METHOD_COOKIE));
    }

    /**
     * 为用户签发会话并装配认证成功结果
     */
    private IssuedSession issue(UserRecord user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        long now = System.currentTimeMillis();
        sessionRepository.insert(
                new AuthSessionRecord(token, user.id(), now, now + AuthConstant.SESSION_TTL.toMillis()));
        return new IssuedSession(CurrentUserVO.from(user, AuthConstant.AUTH_METHOD_COOKIE), token);
    }
}
