package ai.openagent.bootstrap.channel;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Rejects Redis coordination with a database that cannot support multi-Pod claims. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openagent.channel.bus", havingValue = "redis")
public class RedisChannelModeValidator {

    private final DataSource dataSource;

    @PostConstruct
    void validatePostgreSql() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:postgresql:")) {
                throw new IllegalStateException("openagent.channel.bus=redis requires PostgreSQL");
            }
        }
    }
}
