package ai.openagent.bootstrap.channel.controller.vo;

import java.util.List;

public record ChannelMessageDetailVO(
        ChannelMessageVO message,
        List<ChannelTraceStepVO> timeline) {}
