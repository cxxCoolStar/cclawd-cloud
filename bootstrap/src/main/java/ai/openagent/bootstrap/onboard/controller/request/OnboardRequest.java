package ai.openagent.bootstrap.onboard.controller.request;

/**
 * Onboard 初始化请求（V8 M3，POST /api/onboard）
 *
 * <p>
 * 字段形状对齐前端 {@code OnboardRequest}（frontend/src/lib/api.ts）；
 * V8 单机单用户模式下只消费 provider/apiBase/apiKey/model/agentName：
 * admin 账户字段（username/email/password/displayName）留待 V9 认证落地，
 * sandbox 字段仍由环境变量配置（见 application.yml），均接收但忽略
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
