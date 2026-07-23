package ai.openagent.bootstrap.channel.dao.bo;

import lombok.Data;

@Data
public class ChannelMessageRowBO {
    private String id;
    private String channelType;
    private String accountId;
    private String displayName;
    private String senderId;
    private String text;
    private String inboxStatus;
    private String outboxStatus;
    private String runId;
    private Integer attempts;
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
    private Long sentAt;
}
