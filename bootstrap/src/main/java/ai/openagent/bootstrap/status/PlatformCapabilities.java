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
        // mcp 能力 V6 已交付（V7 M3 修复恒 false 的遗留值）
        return new PlatformCapabilities(false, false, false, false, dockerSandboxEnabled, true, false);
    }
}

