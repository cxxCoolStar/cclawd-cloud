package ai.openagent.bootstrap.channel.dao.mapper;

import ai.openagent.bootstrap.channel.dao.bo.ChannelAccountRowBO;
import ai.openagent.bootstrap.channel.dao.bo.ChannelMessageRowBO;
import ai.openagent.bootstrap.channel.dao.bo.ChannelSummaryRowBO;
import ai.openagent.bootstrap.channel.dao.entity.ChannelMessageInboxDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only projections for the Channel operations console. */
@Mapper
public interface ChannelObservationMapper extends BaseMapper<ChannelMessageInboxDO> {

    @Select("""
            SELECT b.id AS "id", b.agent_id AS "agentId", b.channel_type AS "channelType",
                   b.account_id AS "accountId", b.display_name AS "displayName", b.enabled AS "enabled",
                   b.shared_identity AS "sharedIdentity", b.created_at AS "createdAt", b.updated_at AS "updatedAt",
                   (SELECT COUNT(*) FROM channel_message_inbox i
                      WHERE i.binding_id = b.id AND i.status IN ('RECEIVED','PUBLISHED','PROCESSING','RETRY_WAIT'))
                      AS "inboxBacklog",
                   (SELECT COUNT(*) FROM channel_message_outbox o
                      WHERE o.binding_id = b.id AND o.status IN ('READY','PUBLISHED','DELIVERING','RETRY_WAIT'))
                      AS "outboxBacklog"
              FROM channel_bindings b
             WHERE 1 = 1
             ORDER BY b.created_at DESC
            """)
    List<ChannelAccountRowBO> selectAccounts(@Param("userId") String userId);

    @Select("""
            <script>
            SELECT i.id AS "id", b.channel_type AS "channelType", b.account_id AS "accountId",
                   b.display_name AS "displayName", c.chatter_id AS "senderId", i.text AS "text",
                   i.status AS "inboxStatus", o.status AS "outboxStatus", i.run_id AS "runId",
                   CASE WHEN i.attempts >= COALESCE(o.attempts, 0) THEN i.attempts ELSE COALESCE(o.attempts, 0) END AS "attempts",
                   COALESCE(NULLIF(o.last_error, ''), NULLIF(i.last_error, '')) AS "lastError",
                   i.created_at AS "createdAt", CASE WHEN i.updated_at >= COALESCE(o.updated_at, i.updated_at) THEN i.updated_at ELSE o.updated_at END AS "updatedAt",
                   o.sent_at AS "sentAt"
              FROM channel_message_inbox i
              JOIN channel_bindings b ON b.id = i.binding_id
              JOIN channel_conversations c ON c.id = i.conversation_id
              LEFT JOIN channel_message_outbox o ON o.inbox_id = i.id
             WHERE 1 = 1
            <if test="channelType != null and channelType != ''"> AND b.channel_type = #{channelType}</if>
            <if test="accountId != null and accountId != ''"> AND b.account_id = #{accountId}</if>
            <if test="status != null and status != '' and status != 'FAILURES'"> AND (i.status = #{status} OR o.status = #{status})</if>
            <if test="status == 'FAILURES'"> AND (i.status IN ('INTERRUPTED','DEAD') OR o.status IN ('INTERRUPTED','DEAD'))</if>
            <if test="keyword != null and keyword != ''">
              AND (i.id LIKE ('%' || #{keyword} || '%')
                OR i.external_message_id LIKE ('%' || #{keyword} || '%')
                OR i.text LIKE ('%' || #{keyword} || '%')
                OR i.run_id LIKE ('%' || #{keyword} || '%'))
            </if>
             ORDER BY i.created_at DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChannelMessageRowBO> selectMessages(
            @Param("userId") String userId,
            @Param("channelType") String channelType,
            @Param("accountId") String accountId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM channel_message_inbox i
              JOIN channel_bindings b ON b.id = i.binding_id
              LEFT JOIN channel_message_outbox o ON o.inbox_id = i.id
             WHERE 1 = 1
            <if test="channelType != null and channelType != ''"> AND b.channel_type = #{channelType}</if>
            <if test="accountId != null and accountId != ''"> AND b.account_id = #{accountId}</if>
            <if test="status != null and status != '' and status != 'FAILURES'"> AND (i.status = #{status} OR o.status = #{status})</if>
            <if test="status == 'FAILURES'"> AND (i.status IN ('INTERRUPTED','DEAD') OR o.status IN ('INTERRUPTED','DEAD'))</if>
            <if test="keyword != null and keyword != ''">
              AND (i.id LIKE ('%' || #{keyword} || '%') OR i.text LIKE ('%' || #{keyword} || '%') OR i.run_id LIKE ('%' || #{keyword} || '%'))
            </if>
            </script>
            """)
    long countMessages(
            @Param("userId") String userId,
            @Param("channelType") String channelType,
            @Param("accountId") String accountId,
            @Param("status") String status,
            @Param("keyword") String keyword);

    @Select("""
            SELECT i.id AS "id", b.channel_type AS "channelType", b.account_id AS "accountId",
                   b.display_name AS "displayName", c.chatter_id AS "senderId", i.text AS "text",
                   i.status AS "inboxStatus", o.status AS "outboxStatus", i.run_id AS "runId",
                   CASE WHEN i.attempts >= COALESCE(o.attempts, 0) THEN i.attempts ELSE COALESCE(o.attempts, 0) END AS "attempts",
                   COALESCE(NULLIF(o.last_error, ''), NULLIF(i.last_error, '')) AS "lastError",
                   i.created_at AS "createdAt", CASE WHEN i.updated_at >= COALESCE(o.updated_at, i.updated_at) THEN i.updated_at ELSE o.updated_at END AS "updatedAt",
                   o.sent_at AS "sentAt"
              FROM channel_message_inbox i
              JOIN channel_bindings b ON b.id = i.binding_id
              JOIN channel_conversations c ON c.id = i.conversation_id
              LEFT JOIN channel_message_outbox o ON o.inbox_id = i.id
             WHERE 1 = 1 AND i.id = #{messageId}
            """)
    ChannelMessageRowBO selectMessage(@Param("userId") String userId, @Param("messageId") String messageId);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM channel_bindings WHERE 1 = 1) AS "accountCount",
              (SELECT COUNT(*) FROM channel_message_inbox i JOIN channel_bindings b ON b.id = i.binding_id
                 WHERE 1 = 1 AND i.status IN ('RECEIVED','PUBLISHED','PROCESSING','RETRY_WAIT')) AS "inboxBacklog",
              (SELECT COUNT(*) FROM channel_message_outbox o JOIN channel_bindings b ON b.id = o.binding_id
                 WHERE 1 = 1 AND o.status IN ('READY','PUBLISHED','DELIVERING','RETRY_WAIT')) AS "outboxBacklog",
              (SELECT COUNT(*) FROM channel_message_inbox i JOIN channel_bindings b ON b.id = i.binding_id
                 WHERE 1 = 1 AND i.status = 'INTERRUPTED')
                 + (SELECT COUNT(*) FROM channel_message_outbox o JOIN channel_bindings b ON b.id = o.binding_id
                 WHERE 1 = 1 AND o.status = 'INTERRUPTED') AS "interruptedCount",
              (SELECT COUNT(*) FROM channel_message_inbox i JOIN channel_bindings b ON b.id = i.binding_id
                 WHERE 1 = 1 AND i.status = 'DEAD')
                 + (SELECT COUNT(*) FROM channel_message_outbox o JOIN channel_bindings b ON b.id = o.binding_id
                 WHERE 1 = 1 AND o.status = 'DEAD') AS "deadCount",
              (SELECT COUNT(*) FROM channel_message_inbox i JOIN channel_bindings b ON b.id = i.binding_id
                 WHERE 1 = 1 AND i.created_at >= #{dayStart}) AS "messagesToday"
            """)
    ChannelSummaryRowBO selectSummary(@Param("userId") String userId, @Param("dayStart") long dayStart);
}
