package ai.openagent.bootstrap.channel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bus notification whose acknowledgement is explicit and idempotent. */
public final class ChannelDelivery<T> {

    private final T task;
    private final Runnable acknowledgeAction;
    private final AtomicBoolean acknowledged = new AtomicBoolean();

    public ChannelDelivery(T task, Runnable acknowledgeAction) {
        this.task = Objects.requireNonNull(task, "task");
        this.acknowledgeAction = Objects.requireNonNull(acknowledgeAction, "acknowledgeAction");
    }

    public T task() {
        return task;
    }

    public void acknowledge() {
        if (acknowledged.compareAndSet(false, true)) {
            acknowledgeAction.run();
        }
    }
}
