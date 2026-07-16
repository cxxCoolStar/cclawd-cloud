package ai.openagent.bootstrap.persistence;

public record SessionEventRecord(long seq, String eventType, String eventData) {}
