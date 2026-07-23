package ai.openagent.bootstrap.channel.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Durable outbound channel message. */
@Data
@TableName("channel_message_outbox")
public class ChannelMessageOutboxDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String inboxId;
    private String bindingId;
    private String conversationId;
    private String runId;
    private Long sequenceNo;
    private String chatId;
    private String text;
    private String status;
    private Integer attempts;
    private Long availableAt;
    private String claimedBy;
    private Long claimExpiresAt;
    private String providerMessageId;
    private String lastError;
    private Long publishedAt;
    private Long sentAt;
    private Long createdAt;
    private Long updatedAt;
}
