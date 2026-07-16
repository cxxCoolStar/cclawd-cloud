package ai.openagent.bootstrap.persistence;

public record ChatMessageRecord(long seq, String role, String content, String provider, String model, long createdAt) {}
