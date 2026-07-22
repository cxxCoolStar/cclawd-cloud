package ai.openagent.bootstrap.channel.wechat;

import ai.openagent.bootstrap.channel.ChannelInboundMessage;
import ai.openagent.bootstrap.channel.ImChannelAdapter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** Single-account iLink long-poll adapter. */
@Slf4j
public class WechatChannelAdapter implements ImChannelAdapter {

    private static final int SESSION_EXPIRED = -14;
    private static final int EMPTY_CURSOR_EXPIRY_THRESHOLD = 20;
    private static final Duration BACKOFF_INITIAL = Duration.ofSeconds(3);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(60);

    private final WechatCredentials credentials;
    private final WechatILinkClient client;
    private final Consumer<String> cursorSink;
    private final ExecutorService poller;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile String cursor;
    private volatile String status = "stopped";
    private volatile Consumer<ChannelInboundMessage> inboundHandler;

    public WechatChannelAdapter(
            WechatCredentials credentials,
            WechatILinkClient client,
            String initialCursor,
            Consumer<String> cursorSink) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.client = Objects.requireNonNull(client, "client");
        this.cursor = initialCursor == null ? "" : initialCursor;
        this.cursorSink = Objects.requireNonNull(cursorSink, "cursorSink");
        this.poller = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wechat-poll-" + credentials.accountId());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String accountId() {
        return credentials.accountId();
    }

    @Override
    public void start(Consumer<ChannelInboundMessage> inboundHandler) {
        this.inboundHandler = Objects.requireNonNull(inboundHandler, "inboundHandler");
        if (running.compareAndSet(false, true)) {
            status = "connecting";
            log.info(
                    "[channel-trace] WeChat polling started, accountId={}, cursorPresent={}",
                    credentials.accountId(), !cursor.isBlank());
            poller.execute(this::pollLoop);
        }
    }

    @Override
    public void send(String chatId, String text, String contextToken) {
        log.info(
                "[channel-trace] WeChat send started, accountId={}, textLength={}",
                credentials.accountId(), length(text));
        client.sendText(chatId, text, contextToken);
        log.info("[channel-trace] WeChat send completed, accountId={}", credentials.accountId());
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void close() {
        running.set(false);
        status = "stopped";
        poller.shutdownNow();
        log.info("[channel-trace] WeChat polling stopped, accountId={}", credentials.accountId());
    }

    private void pollLoop() {
        int failures = 0;
        int emptyCursorExpired = 0;
        while (running.get()) {
            try {
                WechatILinkClient.PollResult response = client.poll(cursor);
                failures = 0;
                if (response.errorCode() == SESSION_EXPIRED) {
                    if (!cursor.isBlank()) {
                        updateCursor("");
                        sleep(Duration.ofSeconds(5));
                        continue;
                    }
                    emptyCursorExpired++;
                    if (emptyCursorExpired >= EMPTY_CURSOR_EXPIRY_THRESHOLD) {
                        status = "expired";
                        running.set(false);
                        log.warn("[channel] WeChat credential expired, accountId={}", credentials.accountId());
                        return;
                    }
                    failures = emptyCursorExpired;
                    sleep(backoff(failures));
                    continue;
                }
                emptyCursorExpired = 0;
                if (response.ret() != 0 && response.errorCode() != 0) {
                    failures++;
                    status = "degraded";
                    log.warn(
                            "[channel] WeChat poll rejected, accountId={}, ret={}, errCode={}, message={}",
                            credentials.accountId(), response.ret(), response.errorCode(), response.errorMessage());
                    sleep(backoff(failures));
                    continue;
                }
                status = "connected";
                if (!response.messages().isEmpty()) {
                    log.info(
                            "[channel-trace] WeChat poll received messages, accountId={}, count={}",
                            credentials.accountId(), response.messages().size());
                }
                for (ChannelInboundMessage message : response.messages()) {
                    log.info(
                            "[channel-trace] WeChat inbound received, accountId={}, externalMessageId={}, textLength={}",
                            credentials.accountId(), message.messageId(), length(message.text()));
                    inboundHandler.accept(message);
                }
                if (!response.cursor().isBlank() && !response.cursor().equals(cursor)) {
                    updateCursor(response.cursor());
                }
            } catch (RuntimeException error) {
                if (!running.get()) {
                    return;
                }
                failures++;
                status = "degraded";
                Duration delay = backoff(failures);
                log.warn(
                        "[channel] WeChat poll failed, accountId={}, retryIn={}",
                        credentials.accountId(), delay, error);
                sleep(delay);
            }
        }
    }

    private void updateCursor(String value) {
        cursor = value;
        cursorSink.accept(value);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    static Duration backoff(int failures) {
        long multiplier = 1L << Math.min(Math.max(0, failures - 1), 20);
        long millis = Math.min(BACKOFF_INITIAL.toMillis() * multiplier, BACKOFF_MAX.toMillis());
        return Duration.ofMillis(millis);
    }
}
