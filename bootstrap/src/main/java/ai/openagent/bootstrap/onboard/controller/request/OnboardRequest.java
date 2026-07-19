package ai.openagent.bootstrap.onboard.controller.request;

/**
 * Onboard 初始化请求（V8 M3，POST /api/onboard）
 *
 * <p>
 * 字段形状对齐前端 {@code OnboardRequest}（frontend/src/lib/api.ts）；
 * V9 M2 起账密字段（username/email/password/displayName）在全新部署时
 * 用于创建 super_admin 账号；sandbox 字段仍由环境变量配置（见
 * application.yml），接收但忽略
 * </p>
 */
public record OnboardRequest(
        String username,
        String email,
        String password,
        String displayName,
        String provider,
        String apiBase,
        String apiKey,
        String apiType,
        String authType,
        String model,
        String agentName,
        Boolean sandboxEnabled,
        String sandboxBackend,
        String sandboxImage,
        String sandboxE2BKey,
        String sandboxBoxliteUrl,
        String sandboxBoxliteClientId,
        String sandboxBoxliteKey,
        String sandboxBoxlitePrefix) {}
