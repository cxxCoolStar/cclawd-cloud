package ai.openagent.bootstrap.channel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/channel-observation-endpoints-test.db",
            "openagent.channel.roles=api"
        })
@AutoConfigureMockMvc
class ChannelObservationEndpointsTest {

    private static final String BINDING_ID = "observation-binding";
    private static final String MESSAGE_ID = "observation-message";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedMessage() {
        jdbc.update("DELETE FROM channel_message_inbox WHERE binding_id = ?", BINDING_ID);
        jdbc.update("DELETE FROM channel_conversations WHERE binding_id = ?", BINDING_ID);
        jdbc.update("DELETE FROM channel_bindings WHERE id = ?", BINDING_ID);
        String agentId = jdbc.queryForObject(
                "SELECT id FROM agents WHERE user_id = 'local-user' ORDER BY created_at LIMIT 1", String.class);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO channel_bindings
                    (id, user_id, agent_id, channel_type, account_id, display_name, credentials_json,
                     enabled, shared_identity, state_json, created_at, updated_at)
                VALUES (?, 'local-user', ?, 'wechat', 'observation-account', 'Observation', '{}', 1, 0, '{}', ?, ?)
                """, BINDING_ID, agentId, now, now);
        String conversationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO channel_conversations
                    (id, binding_id, chat_id, chatter_id, session_id, context_token, next_sequence, created_at, updated_at)
                VALUES (?, ?, 'chat-1', 'sender-1', 'session-1', '', 1, ?, ?)
                """, conversationId, BINDING_ID, now, now);
        jdbc.update("""
                INSERT INTO channel_message_inbox
                    (id, binding_id, conversation_id, external_message_id, sequence_no, text, context_token,
                     status, attempts, available_at, last_error, created_at, updated_at)
                VALUES (?, ?, ?, 'external-1', 1, 'hello channel', '', 'RECEIVED', 0, ?, '', ?, ?)
                """, MESSAGE_ID, BINDING_ID, conversationId, now, now, now);
    }

    @Test
    void exposesResultWrappedSummaryAccountsAndMessages() throws Exception {
        mockMvc.perform(get("/api/channels/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accountCount").value(1))
                .andExpect(jsonPath("$.data.inboxBacklog").value(1));

        mockMvc.perform(get("/api/channels/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].type").value("wechat"))
                .andExpect(jsonPath("$.data[0].accountId").value("observation-account"))
                .andExpect(jsonPath("$.data[0].clusterStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data[0].adapterStatus").value(""))
                .andExpect(jsonPath("$.data[0].runtimeStatus").doesNotExist());

        mockMvc.perform(get("/api/channels/messages").param("keyword", "hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(MESSAGE_ID));

        mockMvc.perform(get("/api/channels/messages/{messageId}", MESSAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.timeline[0].stage").value("INBOX"));
    }
}
