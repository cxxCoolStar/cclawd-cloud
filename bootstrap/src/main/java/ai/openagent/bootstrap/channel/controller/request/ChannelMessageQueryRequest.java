package ai.openagent.bootstrap.channel.controller.request;

import lombok.Data;

/** Query parameters for the paged message trace view. */
@Data
public class ChannelMessageQueryRequest {

    private String channelType;
    private String accountId;
    private String status;
    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 20;

    public int safePage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int safePageSize() {
        return pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);
    }
}
