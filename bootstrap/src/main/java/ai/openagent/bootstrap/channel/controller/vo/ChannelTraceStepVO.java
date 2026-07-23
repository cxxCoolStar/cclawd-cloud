package ai.openagent.bootstrap.channel.controller.vo;

public record ChannelTraceStepVO(
        String stage,
        String status,
        String detail,
        String startedAt,
        String completedAt) {}
