package ai.openagent.bootstrap.persistence;

public record ChatSessionRecord(String id, String title, String preview, String channel, long createdAt, long updatedAt) {}
