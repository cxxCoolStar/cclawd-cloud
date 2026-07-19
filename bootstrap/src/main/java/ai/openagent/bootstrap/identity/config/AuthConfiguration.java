package ai.openagent.bootstrap.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证配置
 *
 * <p>
 * 只引入 spring-security-crypto 的 BCrypt 散列能力，不引入完整
 * Spring Security（认证过滤器为手写实现）
 * </p>
 */
@Configuration
public class AuthConfiguration {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
