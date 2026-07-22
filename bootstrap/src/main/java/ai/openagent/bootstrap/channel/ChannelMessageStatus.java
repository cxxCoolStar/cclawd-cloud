package ai.openagent.bootstrap.channel;

/** Durable lifecycle states shared by channel inbox and outbox records. */
public enum ChannelMessageStatus {
    RECEIVED,
    PUBLISHED,
    PROCESSING,
    COMPLETED,
    INTERRUPTED,
    READY,
    DELIVERING,
    SENT,
    RETRY_WAIT,
    DEAD
}
