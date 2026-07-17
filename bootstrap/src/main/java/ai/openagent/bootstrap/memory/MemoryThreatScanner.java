package ai.openagent.bootstrap.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆写入安全扫描器（对照 fastclaw internal/privacy/scanner.go）
 *
 * <p>
 * 三组规则与 fastclaw 对齐：提示注入（ignore previous instructions 等）、
 * 凭证泄漏（私钥 / AKIA / ghp_ / xoxb / Discord token）、SSH 后门
 * （authorized_keys、curl|bash）。语义保持一致：检出威胁只用于告警，
 * 不阻断写入（避免数据丢失）
 * </p>
 */
public final class MemoryThreatScanner {

    /**
     * 一次命中：规则分组、命中的模式、上下文摘要
     */
    public record Threat(String type, String pattern, String context) {}

    private record RuleGroup(String type, List<Pattern> patterns) {}

    private static final int CONTEXT_RADIUS = 40;

    private static final List<RuleGroup> GROUPS = List.of(
            new RuleGroup("prompt_injection", List.of(
                    Pattern.compile("(?i)ignore\\s+previous\\s+instructions"),
                    Pattern.compile("(?i)disregard\\s+all\\s+prior"),
                    Pattern.compile("(?i)you\\s+are\\s+now\\b"),
                    Pattern.compile("(?i)forget\\s+everything"),
                    Pattern.compile("(?i)new\\s+persona"),
                    Pattern.compile("(?i)act\\s+as\\s+[^a-z]"))),
            new RuleGroup("credential_leak", List.of(
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    Pattern.compile("\\bAKIA[A-Z0-9]{16}\\b"),
                    Pattern.compile("\\bghp_[A-Za-z0-9]{36,}\\b"),
                    Pattern.compile("\\bxoxb-[A-Za-z0-9\\-]+\\b"),
                    Pattern.compile("\\d{18,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{20,}"))),
            new RuleGroup("ssh_backdoor", List.of(
                    Pattern.compile("(?i)authorized_keys"),
                    Pattern.compile("(?i)(?:curl|wget)\\s+[^\\s]+\\s*\\|\\s*(?:bash|sh)"))));

    private MemoryThreatScanner() {}

    /**
     * 扫描文本，返回全部命中（无命中返回空列表）
     */
    public static List<Threat> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Threat> threats = new ArrayList<>();
        for (RuleGroup group : GROUPS) {
            for (Pattern pattern : group.patterns()) {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    threats.add(new Threat(group.type(), pattern.pattern(), contextOf(text, matcher.start())));
                }
            }
        }
        return threats;
    }

    /**
     * 命中点前后摘要（日志用，半径 40 字符对齐可读性，不含完整内容）
     */
    private static String contextOf(String text, int start) {
        int from = Math.max(0, start - CONTEXT_RADIUS);
        int to = Math.min(text.length(), start + CONTEXT_RADIUS);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
