package ai.openagent.bootstrap.channel.controller.vo;

import java.util.List;

public record ChannelMessagePageVO(
        List<ChannelMessageVO> items,
        long total,
        int page,
        int pageSize,
        boolean hasNext) {}
