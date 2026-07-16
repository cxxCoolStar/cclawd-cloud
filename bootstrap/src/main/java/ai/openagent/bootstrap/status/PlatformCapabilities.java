package ai.openagent.bootstrap.status;

public record PlatformCapabilities(
        boolean channels,
        boolean plugins,
        boolean projectRuntime,
        boolean multiPod,
        boolean dockerSandbox,
        boolean mcp,
        boolean cron) {

    public static PlatformCapabilities v1Defaults(boolean dockerSandboxEnabled) {
        return new PlatformCapabilities(false, false, false, false, dockerSandboxEnabled, false, false);
    }
}

