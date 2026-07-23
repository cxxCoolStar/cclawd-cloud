package ai.openagent.bootstrap.channel.dao.bo;

import lombok.Data;

@Data
public class ChannelSummaryRowBO {
    private Long accountCount;
    private Long inboxBacklog;
    private Long outboxBacklog;
    private Long interruptedCount;
    private Long deadCount;
    private Long messagesToday;
}
