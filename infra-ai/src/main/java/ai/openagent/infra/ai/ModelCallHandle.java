package ai.openagent.infra.ai;

public interface ModelCallHandle {

    void cancel();

    boolean isDone();
}

