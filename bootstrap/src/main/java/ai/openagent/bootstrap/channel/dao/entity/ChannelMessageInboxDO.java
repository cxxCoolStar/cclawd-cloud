package ai.openagent.bootstrap.channel.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Durable inbound channel message. */
@Data
@TableName("channel_message_inbox")
public class ChannelMessageInboxDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String bindingId;
    private String conversationId;
    private String externalMessageId;
    private Long sequenceNo;
    private String text;
    private String status;
    private Integer attempts;
    private Long availableAt;
    private String claimedBy;
    private Long claimExpiresAt;
    private String runId;
    private String lastError;
    private Long publishedAt;
    private Long completedAt;
    private Long createdAt;
    private Long updatedAt;
}
