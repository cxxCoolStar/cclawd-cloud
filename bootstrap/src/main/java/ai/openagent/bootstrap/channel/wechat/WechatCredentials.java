package ai.openagent.bootstrap.channel.wechat;

import java.util.Objects;

/** Credentials returned by a confirmed iLink QR login. */
public record WechatCredentials(String botToken, String accountId, String baseUrl, String ilinkUserId) {

    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    public WechatCredentials {
        botToken = requireText(botToken, "botToken");
        accountId = requireText(accountId, "accountId");
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        ilinkUserId = Objects.requireNonNullElse(ilinkUserId, "");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
