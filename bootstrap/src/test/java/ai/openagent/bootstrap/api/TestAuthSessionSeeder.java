package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AuthSessionRecord;
import ai.openagent.bootstrap.persistence.AuthSessionRepository;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.bootstrap.persistence.UserRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 测试会话种子（仅测试代码）
 *
 * <p>
 * 保证每个测试库中：种子 local-user 就位（与 DataSeeder 同样幂等，
 * 不依赖 Runner 执行顺序），且存在 {@link TestIdentity#TEST_SESSION_TOKEN}
 * 对应的有效会话行——TestAuthSessionFilter 注入的 cookie 走的是
 * AuthFilter 的真实查库校验路径
 * </p>
 */
@Component
@RequiredArgsConstructor
public class TestAuthSessionSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;

    @Override
    public void run(ApplicationArguments args) {
        long now = System.currentTimeMillis();
        if (userRepository.findById(IdentityConstant.LOCAL_USER_ID).isEmpty()) {
            userRepository.insert(new UserRecord(
                    IdentityConstant.LOCAL_USER_ID,
                    "local",
                    "local@openagent.invalid",
                    "super_admin",
                    "Local User",
                    "active",
                    "",
                    "",
                    -1,
                    now));
        }
        if (sessionRepository.findValid(TestIdentity.TEST_SESSION_TOKEN, now).isEmpty()) {
            sessionRepository.insert(new AuthSessionRecord(
                    TestIdentity.TEST_SESSION_TOKEN,
                    IdentityConstant.LOCAL_USER_ID,
                    now,
                    now + Duration.ofDays(30).toMillis()));
        }
    }
}
