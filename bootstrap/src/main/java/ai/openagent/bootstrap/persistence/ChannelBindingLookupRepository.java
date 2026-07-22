package ai.openagent.bootstrap.persistence;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only binding lookup used by distributed channel workers. */
@Repository
@RequiredArgsConstructor
public class ChannelBindingLookupRepository {

    private final JdbcTemplate jdbc;

    public Optional<ChannelBindingRecord> findById(String id) {
        return jdbc.query(
                        "SELECT * FROM channel_bindings WHERE id = ?",
                        (rs, row) -> new ChannelBindingRecord(
                                rs.getString("id"), rs.getString("user_id"), rs.getString("agent_id"),
                                rs.getString("channel_type"), rs.getString("account_id"),
                                rs.getString("display_name"), rs.getString("credentials_json"),
                                rs.getBoolean("enabled"), rs.getBoolean("shared_identity"),
                                rs.getString("state_json"), rs.getLong("created_at"),
                                rs.getLong("updated_at")),
                        id)
                .stream().findFirst();
    }
}
