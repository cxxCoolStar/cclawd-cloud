package ai.openagent.bootstrap.channel.dao.bo;

import lombok.Data;

@Data
public class ChannelAccountRowBO {
    private String id;
    private String agentId;
    private String channelType;
    private String accountId;
    private String displayName;
    private Boolean enabled;
    private Boolean sharedIdentity;
    private Long createdAt;
    private Long updatedAt;
    private Long inboxBacklog;
    private Long outboxBacklog;
}
